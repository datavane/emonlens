#!/usr/bin/env bash
# =====================================================================
# EmonLens 发布冒烟：对运行中的实例做健康检查 + 登录 + 核心 API 探测
#   用法：scripts/smoke.sh [BASE_URL] [USER] [PASS]
#   密码必填：产品不提供默认口令，缺 PASS 时直接退出（退出码 2）
#   退出码：0=全部通过；1=存在失败项；2=用法/参数错误
# =====================================================================
set -uo pipefail

BASE="${1:-http://127.0.0.1:8300}"
USER_NAME="${2:-admin}"
PASS_WORD="${3:-}"

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }

# check <名称> <URL> [curl 参数...]：仅验 HTTP 2xx（用于 /actuator 等非包络端点）
check() {
  local name="$1" url="$2"; shift 2
  local body code
  body=$(curl -sS -m 10 -w '\n%{http_code}' "$@" "$url" 2>/dev/null)
  code=$(printf '%s' "$body" | tail -n1)
  if [[ "$code" =~ ^2 ]]; then ok "$name ($code)"; else bad "$name (HTTP $code) $url"; fi
}

# checkApi <名称> <URL> [curl 参数...]：平台契约为 HTTP 200 + 业务码体，须同时断言 code==0
checkApi() {
  local name="$1" url="$2"; shift 2
  local body out
  body=$(curl -sS -m 10 "$@" "$url" 2>/dev/null)
  out=$(printf '%s' "$body" | python3 -c 'import sys,json
try:
    print(json.load(sys.stdin).get("code"))
except Exception:
    print("PARSE_ERROR")' 2>/dev/null)
  if [[ "$out" == "0" ]]; then ok "$name"; else bad "$name [code=$out] $(printf '%s' "$body" | head -c 160)"; fi
}

# checkPostApi <名称> <URL> <JSON body> [curl 参数...]：POST + 业务码断言
checkPostApi() {
  local name="$1" url="$2" payload="$3"; shift 3
  checkApi "$name" "$url" "$@" -X POST -H 'Content-Type: application/json' -d "$payload"
}

echo "== EmonLens smoke @ $BASE =="

if [[ -z "$PASS_WORD" ]]; then
  echo "错误：缺少密码参数。产品不提供默认口令，请显式传入 admin 密码：" >&2
  echo "  make smoke PASS=<密码>                # 或 scripts/smoke.sh $BASE admin <密码>" >&2
  exit 2
fi

# 1. 健康检查（不鉴权）
check "actuator/health" "$BASE/actuator/health"

# 2. 登录拿 JWT
LOGIN=$(curl -sS -m 10 -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASS_WORD\"}" 2>/dev/null)
TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import sys,json
try:
    d = json.load(sys.stdin)
    print((d.get("data") or {}).get("token") or "")
except Exception:
    print("")' 2>/dev/null)
if [[ -n "$TOKEN" ]]; then
  ok "auth/login (JWT 获取)"
else
  LOGIN_CODE=$(printf '%s' "$LOGIN" | python3 -c 'import sys,json
try:
    print(json.load(sys.stdin).get("code"))
except Exception:
    print("PARSE_ERROR")' 2>/dev/null)
  bad "auth/login：$(printf '%s' "$LOGIN" | head -c 200)"
  case "$LOGIN_CODE" in
    40101) echo "  提示：用户名或密码不匹配（40101）。用实际密码重跑：make smoke PASS=<密码>" ;;
    50301) echo "  提示：触发登录限流/失败锁定（50301），等锁定窗口结束后再试" ;;
    503)   echo "  提示：服务端拒绝（503），可能未就绪或安全门禁未通过，看服务端日志" ;;
  esac
fi

if [[ -z "$TOKEN" ]]; then
  echo "== 结果：PASS=$PASS FAIL=$FAIL [无 token，跳过业务 API 探测] =="
  exit 1
fi

AUTH=(-H "Authorization: Bearer $TOKEN")

# 3. 控制面核心 API（登录后可读；平台契约 HTTP 200 + 业务码体，断言 code==0）
checkApi "auth/me"             "$BASE/api/v1/auth/me"            "${AUTH[@]}"
checkApi "dashboard/summary"   "$BASE/api/v1/dashboard/summary"  "${AUTH[@]}"
checkApi "observations/stats"  "$BASE/api/v1/observations/stats" "${AUTH[@]}"
checkPostApi "traces 搜索"     "$BASE/api/v1/traces/search" '{"pageNumber":1,"pageSize":1}' "${AUTH[@]}"
checkApi "sessions 列表"       "$BASE/api/v1/sessions?pageNumber=1&pageSize=1" "${AUTH[@]}"
checkApi "prompts 列表"        "$BASE/api/v1/prompts?pageNumber=1&pageSize=1" "${AUTH[@]}"
checkApi "agents 列表"         "$BASE/api/v1/agents"             "${AUTH[@]}"
checkApi "sys/menus"           "$BASE/api/v1/sys/menus"          "${AUTH[@]}"

# 4. stats 字段契约（限流计数字段须存在，缺失说明版本不匹配）
STATS=$(curl -sS -m 10 "$BASE/api/v1/observations/stats" "${AUTH[@]}" 2>/dev/null)
if printf '%s' "$STATS" | grep -q 'rateLimitedTotal'; then
  ok "observations/stats 字段契约 [含 rateLimitedTotal]"
else
  bad "observations/stats 缺少 rateLimitedTotal：$(printf '%s' "$STATS" | head -c 200)"
fi

# 5. SPA 入口（前端产物须内嵌）
INDEX=$(curl -sS -m 10 -o /dev/null -w '%{http_code}' "$BASE/" 2>/dev/null)
if [[ "$INDEX" == "200" ]]; then ok "SPA index.html ($INDEX)"; else bad "SPA index.html (HTTP $INDEX)——前端产物未内嵌？"; fi

# 6. 数据面凭据校验（平台契约：拒绝也返回 HTTP 200 + 业务码体，code 须非 0 且非 5xx）
INGEST_BODY=$(curl -sS -m 10 -X POST "$BASE/api/v1/ingest/events" \
  -H 'Content-Type: application/json' -H 'X-Trust-Key: smoke-invalid-key' \
  -d '[]' 2>/dev/null)
INGEST_CODE=$(printf '%s' "$INGEST_BODY" | python3 -c 'import sys,json
try:
    print(json.load(sys.stdin).get("code"))
except Exception:
    print("PARSE_ERROR")' 2>/dev/null)
if [[ "$INGEST_CODE" != "0" && "$INGEST_CODE" != "PARSE_ERROR" && "$INGEST_CODE" != "None" ]]; then
  ok "ingest 错误 key 拒绝 [业务码 $INGEST_CODE]"
else
  bad "ingest 错误 key 未被拒绝：$(printf '%s' "$INGEST_BODY" | head -c 200)"
fi

echo "== 结果：PASS=$PASS FAIL=$FAIL =="
[[ $FAIL -eq 0 ]]
