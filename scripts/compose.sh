#!/usr/bin/env bash
# docker compose 包装：只读命令在 .env 缺失时也能用。
#
# docker-compose.yml 用 ${VAR:?msg} 强制校验安全凭据。这是故意的——但它同时会让
# ps / logs / down 这类只需要项目名和容器名的只读命令一起插值失败，用户想看日志时
# 只得到一句 "required variable MYSQL_ROOT_PASSWORD is missing a value"。
#
# 本脚本在缺 .env 时改用 scripts/compose-required.env 占位凭据满足插值，并只放行
# 只读子命令；涉及启停的命令一律要求真实 .env，避免用占位凭据把栈拉起来。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 不需要真实凭据即可执行的命令：只涉及项目名/容器名，不读任何口令。
# 注意：up / restart 之类**不能**进来——那会用公开的占位值把服务拉起来。
readonly SUBCOMMANDS=(ps logs top ls events images down)

is_readonly() {
  local sub="$1" n i
  n=${#SUBCOMMANDS[@]}
  for ((i = 0; i < n; i++)); do
    if [ "${SUBCOMMANDS[$i]}" = "$sub" ]; then return 0; fi
  done
  return 1
}

if [ "$#" -eq 0 ]; then
  echo "用法: compose.sh <compose 子命令> [参数...]" >&2
  exit 2
fi

if [ -f "$ROOT/.env" ]; then
  exec docker compose "$@"
fi

if is_readonly "$1"; then
  echo "提示：未找到 .env，本次以占位凭据插值（不影响已运行的容器）" >&2
  echo "      需要启停生产栈请先：cp .env.example .env 并填写必填项" >&2
  if [ "$1" = "down" ]; then
    echo "      注意：down 会停止并移除容器（数据卷保留）" >&2
  fi
  exec docker compose --env-file scripts/compose-required.env "$@"
fi

cat >&2 <<'EOF'
错误：当前目录没有 .env，无法启动/修改生产栈。

  cp .env.example .env   # 然后填写里面的必填项
  make up

只想看状态 / 日志 / 停止栈的话不需要 .env：make ps、make logs、make down。
EOF
exit 1
