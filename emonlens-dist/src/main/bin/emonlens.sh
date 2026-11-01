#!/bin/bash
# ============================================================================
# EmonLens 启动脚本（分发件自带）
#
#   bin/emonlens.sh start      后台启动并等待健康检查
#   bin/emonlens.sh stop       优雅停止（SIGTERM，会刷完 ingest 队列）
#   bin/emonlens.sh restart
#   bin/emonlens.sh status
#   bin/emonlens.sh logs [N]   跟随日志（默认 200 行）
#   bin/emonlens.sh console    前台运行（Ctrl-C 停止，便于排障）
#
# 配置：conf/emonlens.env（从 conf/emonlens.env.example 复制）
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

# help/无参数时不加载配置（否则缺配置会先报错，看不到用法）
case "${1:-}" in
    ""|help|-h|--help|usage) EMONLENS_SKIP_VALIDATE=1 ;;
esac
export EMONLENS_SKIP_VALIDATE

# shellcheck source=./setenv.sh
. "$SCRIPT_DIR/setenv.sh"

PID_FILE="$APP_HOME/run/emonlens.pid"
CONSOLE_LOG="$APP_HOME/logs/console.log"

# ---------- 定位 jar / java ----------
JAR=""
for f in "$APP_HOME"/lib/emonlens-server-*.jar; do
    if [ -e "$f" ]; then
        JAR="$f"
        break
    fi
done
if [ -z "$JAR" ]; then
    echo "ERROR: 在 $APP_HOME/lib/ 下未找到 emonlens-server-*.jar" >&2
    exit 1
fi

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
fi
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
    echo "ERROR: 未找到 java（需要 JDK/JRE 11+）；请设置 JAVA_HOME 或把 java 加入 PATH" >&2
    exit 1
fi

# ---------- 工具函数 ----------
is_running() {
    [ -f "$PID_FILE" ] || return 1
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    [ -n "${pid}" ] || return 1
    kill -0 "${pid}" 2>/dev/null
}

# 用 bash /dev/tcp 探活，避免依赖 curl。
# 放在子 shell 里执行：exec 的 fd 操作（重复关闭会失败）不会影响主脚本。
http_ok() {
    local port="$1" line=""
    line=$(
        {
            exec 3<>"/dev/tcp/127.0.0.1/$port" || exit 1
            printf 'GET /actuator/health HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n' >&3 || exit 1
            IFS= read -r l <&3 || true
            printf '%s' "$l"
        } 2>/dev/null
    ) || return 1
    case "$line" in
        *" 200 "*) return 0 ;;
        *) return 1 ;;
    esac
}

java_args() {
    printf '%s\n' \
        $JAVA_OPTS \
        "-Duser.timezone=$TZ" \
        "-jar" "$JAR" \
        "--server.port=${EMONLENS_PORT}" \
        "--spring.config.additional-location=file:$APP_HOME/conf/"
    if [ -n "$SPRING_PROFILES_ACTIVE" ]; then
        printf '%s\n' "--spring.profiles.active=$SPRING_PROFILES_ACTIVE"
    fi
}

# ---------- 命令 ----------
do_start() {
    if is_running; then
        echo "EmonLens 已在运行（pid $(cat "$PID_FILE")）"
        return 0
    fi

    mkdir -p "$APP_HOME/logs" "$APP_HOME/run" "$(dirname "$EMONLENS_LOG_FILE")"
    if [ -f "${CONSOLE_LOG}" ]; then
        mv -f "${CONSOLE_LOG}" "${CONSOLE_LOG}.1"
    fi

    echo "启动 EmonLens（$(head -n1 "$APP_HOME/VERSION" 2>/dev/null || echo "version unknown")）"
    print_config

    # 用数组组装参数，避免 JAVA_OPTS 里的空格被当成单个参数
    local -a args=()
    while IFS= read -r a; do args+=("$a"); done < <(java_args)

    nohup "$JAVA_BIN" "${args[@]}" >>"$CONSOLE_LOG" 2>&1 &
    local pid=$!
    echo "${pid}" >"$PID_FILE"
    echo "pid=${pid}，等待健康检查（最多 ${START_WAIT_SECONDS}s）..."

    local deadline=$((SECONDS + START_WAIT_SECONDS))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! is_running; then
            echo "ERROR: 进程已退出。最后 30 行 ${CONSOLE_LOG}：" >&2
            tail -n 30 "$CONSOLE_LOG" >&2 || true
            rm -f "$PID_FILE"
            return 1
        fi
        if http_ok "$EMONLENS_PORT"; then
            echo "启动成功：http://127.0.0.1:${EMONLENS_PORT}（健康检查通过）"
            return 0
        fi
        sleep 2
    done

    echo "WARN: ${START_WAIT_SECONDS}s 内未通过健康检查（进程仍在运行）" >&2
    echo "      查看日志：bin/emonlens.sh logs 200" >&2
    return 0
}

do_stop() {
    if ! is_running; then
        echo "EmonLens 未在运行"
        rm -f "$PID_FILE"
        return 0
    fi

    local pid
    pid="$(cat "$PID_FILE")"
    echo "停止 EmonLens（pid ${pid}，SIGTERM 优雅停机 / 刷 ingest 队列）..."
    kill "$pid" 2>/dev/null || true

    local waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge 60 ]; then
            echo "WARN: 60s 未退出，强制 kill -9（队列内数据可能丢失）" >&2
            kill -9 "$pid" 2>/dev/null || true
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    rm -f "$PID_FILE"
    echo "已停止（耗时 ${waited}s）"
}

do_status() {
    if is_running; then
        local pid
        pid="$(cat "$PID_FILE")"
        if http_ok "$EMONLENS_PORT"; then
            echo "running（pid ${pid}，http://127.0.0.1:$EMONLENS_PORT 健康）"
        else
            echo "running（pid ${pid}，健康检查未通过；可能正在启动或依赖异常）"
            return 1
        fi
    else
        echo "not running"
        return 1
    fi
}

do_logs() {
    local lines="${1:-200}"
    if [ -f "$EMONLENS_LOG_FILE" ]; then
        tail -n "$lines" -f "$EMONLENS_LOG_FILE"
    elif [ -f "$CONSOLE_LOG" ]; then
        echo "（$EMONLENS_LOG_FILE 尚未生成，跟随 ${CONSOLE_LOG}）" >&2
        tail -n "$lines" -f "$CONSOLE_LOG"
    else
        echo "ERROR: 未找到日志文件（$EMONLENS_LOG_FILE / ${CONSOLE_LOG}）" >&2
        return 1
    fi
}

do_console() {
    echo "前台运行（Ctrl-C 停止）"
    print_config
    local -a args=()
    while IFS= read -r a; do args+=("$a"); done < <(java_args)
    exec "$JAVA_BIN" "${args[@]}"
}

usage() {
    sed -n '5,12p' "$0" | sed 's/^# \{0,1\}//'
}

case "${1:-}" in
    start)   do_start ;;
    stop)    do_stop ;;
    restart) do_stop; do_start ;;
    status)  do_status ;;
    logs)    do_logs "${2:-200}" ;;
    console) do_console ;;
    *)       usage; exit 1 ;;
esac
