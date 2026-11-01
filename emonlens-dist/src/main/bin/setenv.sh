#!/bin/bash
# ============================================================================
# EmonLens 环境初始化（由 bin/emonlens.sh source，不单独执行）
#
# 优先级：conf/emonlens.env（或 EMONLENS_ENV_FILE 指定的文件） > 脚本默认值。
# 想临时覆盖某项，用另一个 env 文件：EMONLENS_ENV_FILE=/tmp/test.env bin/emonlens.sh start
# ============================================================================

: "${APP_HOME:?APP_HOME 未设置（应由 bin/emonlens.sh 传入）}"

# ---------- 加载环境文件 ----------
# 自带 KEY=VALUE 解析（不 source，避免把配置里的空格当命令执行）：
#   - 忽略空行与 # 注释；允许 export 前缀；值两侧引号可省略
#   - 未加引号的值保留空格（JAVA_OPTS=-Xms512m -Xmx1024m 可直接写）
#   - 未加引号时，值里 " #" 之后视为行尾注释
load_env_file() {
    local file="$1" line key value
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line#"${line%%[![:space:]]*}"}"
        case "$line" in
            ''|'#'*) continue ;;
            export\ *) line="${line#export }" ;;
        esac
        case "$line" in
            *=*) ;;
            *) echo "WARN: 忽略无法解析的配置行: $line" >&2; continue ;;
        esac

        key="${line%%=*}"
        key="${key//[[:space:]]/}"
        case "$key" in
            [A-Za-z_][A-Za-z0-9_]*) ;;
            *) echo "WARN: 忽略非法变量名: $key" >&2; continue ;;
        esac

        value="${line#*=}"
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        case "$value" in
            \"*\") value="${value#\"}"; value="${value%\"}" ;;
            \'*\') value="${value#\'}"; value="${value%\'}" ;;
            *)    value="${value%%[[:space:]]#*}" ;;   # 行尾注释
        esac
        export "$key=$value"
    done < "$file"
}

ENV_FILE="${EMONLENS_ENV_FILE:-$APP_HOME/conf/emonlens.env}"
if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
    load_env_file "$ENV_FILE"
fi

# ---------- 默认值（仅对未设置/为空时生效） ----------
: "${SPRING_PROFILES_ACTIVE:=prod}"
: "${EMONLENS_PORT:=8300}"
: "${EMONLENS_LOG_FILE:=$APP_HOME/logs/emonlens.log}"
: "${EMONLENS_LOG_LEVEL:=INFO}"
: "${EMONLENS_WORKER_ENABLED:=true}"
: "${EMONLENS_TRUST_XFF:=false}"
: "${EMONLENS_RATE_LIMIT:=600}"
: "${EMONLENS_INGEST_RATE_LIMIT:=20000}"
: "${EMONLENS_DB_HOST:=127.0.0.1}"
: "${EMONLENS_DB_PORT:=3306}"
: "${EMONLENS_DB_NAME:=emonlens}"
: "${EMONLENS_DB_USER:=emonlens}"
: "${EMONLENS_DB_POOL:=20}"
: "${TZ:=Asia/Shanghai}"
: "${JAVA_OPTS:=-Xms512m -Xmx1024m}"
: "${START_WAIT_SECONDS:=60}"

export SPRING_PROFILES_ACTIVE EMONLENS_PORT EMONLENS_LOG_FILE EMONLENS_LOG_LEVEL \
       EMONLENS_WORKER_ENABLED EMONLENS_TRUST_XFF EMONLENS_RATE_LIMIT \
       EMONLENS_INGEST_RATE_LIMIT EMONLENS_DB_HOST EMONLENS_DB_PORT \
       EMONLENS_DB_NAME EMONLENS_DB_USER EMONLENS_DB_POOL TZ JAVA_OPTS \
       START_WAIT_SECONDS

# ---------- prod 门禁前置校验（与 SecurityGateRunner 同口径，提前报错更好排查） ----------
validate_prod() {
    local missing=0

    check() {
        local name="$1" value="${2:-}" min="${3:-1}"
        if [ -z "$value" ]; then
            echo "ERROR: 缺少 ${name}（conf/emonlens.env）" >&2
            missing=1
        elif [ "${#value}" -lt "${min}" ]; then
            echo "ERROR: $name 长度不得小于 ${min}（当前 ${#value}）" >&2
            missing=1
        fi
    }

    check EMONLENS_JWT_SECRET "${EMONLENS_JWT_SECRET:-}" 16
    check EMONLENS_INGEST_KEY "${EMONLENS_INGEST_KEY:-}" 1
    check EMONLENS_ADMIN_PASSWORD "${EMONLENS_ADMIN_PASSWORD:-}" 8
    check EMONLENS_DB_PASSWORD "${EMONLENS_DB_PASSWORD:-}" 1

    if [ "${EMONLENS_INGEST_KEY:-}" = "dev-trust-key-001" ]; then
        echo "ERROR: EMONLENS_INGEST_KEY 不得使用开发默认值 dev-trust-key-001" >&2
        missing=1
    fi
    if [ -z "${EMONLENS_JWT_SECRET:-}" ] && [ -z "${EMONLENS_INGEST_KEY:-}" ] && [ -z "${EMONLENS_ADMIN_PASSWORD:-}" ] && [ -z "${EMONLENS_DB_PASSWORD:-}" ]; then
        echo "提示: 复制 conf/emonlens.env.example 为 conf/emonlens.env 并填写必填项" >&2
    fi

    [ "$missing" -eq 0 ] || exit 1
}

if [ "$SPRING_PROFILES_ACTIVE" = "prod" ] && [ "${EMONLENS_SKIP_VALIDATE:-0}" != "1" ]; then
    validate_prod
fi

print_config() {
    echo "  profile      : ${SPRING_PROFILES_ACTIVE:-(none)}"
    echo "  port         : $EMONLENS_PORT"
    echo "  db           : $EMONLENS_DB_USER@$EMONLENS_DB_HOST:$EMONLENS_DB_PORT/$EMONLENS_DB_NAME"
    echo "  log          : $EMONLENS_LOG_FILE"
    echo "  worker       : $EMONLENS_WORKER_ENABLED"
    echo "  java opts    : $JAVA_OPTS"
}
