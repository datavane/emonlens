"""传输层：POST /api/v1/ingest/events（urllib 零依赖）。

失败策略：网络/5xx 指数退避重试 2 次；仍失败的批次本地落盘
（<state_dir>/failed/<时间戳>.json，可人工恢复或删除），观测永不阻塞业务。
400（批次非法）：fail-fast 丢弃并告警（重试同样失败）。
401（Key 失效）：本批不落盘直接丢弃并告警一次（Key 修复后自动恢复）。
"""
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

_WARNED_401 = threading.Event()
_MAX_RETRIES = 2
# 服务端把业务错误放在 HTTP 200 的 ApiResult.code 里（GlobalExceptionHandler）：
# 只看 HTTP 状态会把鉴权/校验失败当成成功，数据静默丢失。
_AUTH_CODES = {40101, 40301}
_BAD_REQUEST_CODES = {40000}
_RATE_LIMIT_CODES = {50301}
_RETRYABLE_CODES = {50000, 50001, 50002}


def _debug_log(cfg, msg):
    if cfg.debug:
        _warn("[emonlens]", msg)


_MAX_TEXT_BYTES = 60000  # 服务端 TEXT 列上限 65535 字节，留出 JSON 转义余量


def _truncate_utf8(text: str, max_bytes: int) -> str:
    """按 UTF-8 字节数截断，不切断多字节字符。"""
    encoded = text.encode("utf-8")
    if len(encoded) <= max_bytes:
        return text
    return encoded[:max_bytes].decode("utf-8", "ignore")


def serialize(value, max_len=2048):
    """字符串按服务端 TEXT 上限截断；非基本类型 repr() 截断；序列化失败不抛错。"""
    if value is None or isinstance(value, (int, float, bool)):
        return value
    if isinstance(value, str):
        # 之前直接透传：>64KB 的提示词/输出会让整条 span 被服务端拒收
        return _truncate_utf8(value, _MAX_TEXT_BYTES)
    try:
        text = json.dumps(value, ensure_ascii=False, default=str)
    except Exception:
        text = repr(value)
    return text[:max_len]


def get_json(cfg, path: str, timeout: float = 5.0):
    """GET 控制面数据（prompt 拉取等）。返回 dict；失败抛异常由调用方处理。"""
    req = urllib.request.Request(
        cfg.host.rstrip("/") + path,
        headers={"X-Trust-Key": cfg.ingest_key, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if isinstance(body, dict) and body.get("code") not in (0, None):
        raise RuntimeError(body.get("message") or f"code {body.get('code')}")
    return body.get("data") if isinstance(body, dict) else body


def _warn(*args):
    """运营提示走 stderr：SDK 可能跑在 stdout 被下游消费的管道里。"""
    print("[emonlens]", *args, file=sys.stderr)


def fallback_to_disk(cfg, spans):
    """最终失败兜底：批次写 <debug_dir>/failed/<ts>.json，供人工恢复（不自动重放，避免重复数据）。"""
    try:
        state_dir = os.environ.get("EMONLENS_STATE_DIR", os.path.expanduser("~/.emonlens"))
        failed_dir = os.path.join(state_dir, "failed")
        os.makedirs(failed_dir, exist_ok=True)
        path = os.path.join(failed_dir, time.strftime("%Y%m%d-%H%M%S") + "-"
                            + str(int(time.time() * 1000) % 1000).zfill(3) + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(spans, f, ensure_ascii=False, default=serialize)
        _warn(f"[emonlens] 上报最终失败，{len(spans)} spans 已本地保存: {path}")
    except Exception:
        _warn(f"[emonlens] 上报最终失败且本地保存失败，丢弃 {len(spans)} spans")


def post_spans(cfg, spans) -> bool:
    """上报一批 span。返回是否成功。任何异常都不向外抛。

    服务端 200 响应可能带 rejected[]（部分 span 校验失败被拒收）：逐条落调试日志，
    有拒收时打印一条摘要提醒，避免数据静默丢失。"""
    payload = json.dumps(spans, ensure_ascii=False, default=serialize).encode("utf-8")
    delay = 0.5
    for attempt in range(_MAX_RETRIES + 1):
        try:
            req = urllib.request.Request(
                cfg.events_url, data=payload,
                headers={"X-Trust-Key": cfg.ingest_key,
                         "Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                body = json.loads(resp.read() or b"{}")
                code = body.get("code")
                if code not in (0, None):
                    if code in _AUTH_CODES:
                        if not _WARNED_401.is_set():
                            _WARNED_401.set()
                            _warn(f"[emonlens] Ingest Key 无效（code={code}），本批未上报。"
                                  "请检查 EMONLENS_INGEST_KEY；修复后恢复上报，此提示只出现一次。")
                        return False
                    if code in _BAD_REQUEST_CODES:
                        _warn(f"[emonlens] 批次被服务端拒收（code={code}: {body.get('message')}），"
                              f"{len(spans)} spans 本地保存")
                        fallback_to_disk(cfg, spans)
                        return False
                    if code in _RATE_LIMIT_CODES:
                        wait = 5.0
                        _warn(f"[emonlens] 服务端限流（code={code}），{wait:.0f}s 后重试"
                              "（可降低采样率或联系管理员调整 ingest 限流）")
                        if attempt < _MAX_RETRIES:
                            time.sleep(wait)
                            delay *= 2
                            continue
                        break
                    _debug_log(cfg, f"上报返回业务错误 code={code}: {body.get('message')}")
                    if code not in _RETRYABLE_CODES:
                        fallback_to_disk(cfg, spans)
                        return False
                    if attempt < _MAX_RETRIES:
                        time.sleep(delay)
                        delay *= 2
                        continue
                    break
                data = body.get("data") or {}
                rejected = data.get("rejected") or []
                # 兼容旧字段名 dropped；当前服务端返回 droppedInRequest
                server_dropped = data.get("droppedInRequest", data.get("dropped")) or 0
                if server_dropped:
                    _warn(f"[emonlens] 服务端队列打满，丢弃 {server_dropped} spans"
                          "（Agent 产出速率高于服务端处理速率，可降低采样或扩容）")
                if rejected:
                    for rj in rejected[:5]:
                        _debug_log(cfg, f"span#{rj.get('index')} 被拒收: {rj.get('reason')}")
                    _warn(f"[emonlens] {len(rejected)}/{len(spans)} spans 被服务端拒收"
                          f"（首个原因: {rejected[0].get('reason')}），详见调试日志")
                _debug_log(cfg, f"上报成功 {len(spans)} spans")
                return True
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                if not _WARNED_401.is_set():
                    _WARNED_401.set()
                    _warn("[emonlens] Ingest Key 无效（401），本批未上报。"
                          "请检查 EMONLENS_INGEST_KEY；修复后恢复上报，此提示只出现一次。")
                return False
            if e.code == 429:
                # 服务端限流：按 Retry-After 退避后重试本批（不落盘，额度恢复后继续报）
                retry_after = None
                try:
                    retry_after = e.headers.get("Retry-After")
                except Exception:
                    pass
                try:
                    wait = max(1.0, float(retry_after))
                except (TypeError, ValueError):
                    wait = max(1.0, delay)
                _warn(f"[emonlens] 服务端限流（429），{wait:.0f}s 后重试"
                      "（可降低采样率或联系管理员调整 emonlens.ingest.rate-limit-spans-per-minute）")
                if attempt < _MAX_RETRIES:
                    time.sleep(wait)
                    delay *= 2
                continue
            if e.code == 400:
                # 批次非法（如超过单批上限）：重试同样失败，直接丢弃并告警
                body = ""
                try:
                    body = e.read().decode("utf-8", "replace")[:200]
                except Exception:
                    pass
                _warn(f"[emonlens] 批次被拒收（400），{len(spans)} spans 本地保存: {body}")
                fallback_to_disk(cfg, spans)
                return False
            body = ""
            try:
                body = e.read().decode("utf-8", "replace")[:200]
            except Exception:
                pass
            _debug_log(cfg, f"HTTP {e.code} 重试 {attempt + 1}: {body}")
        except Exception as e:  # 网络/超时
            _debug_log(cfg, f"上报失败 重试 {attempt + 1}: {e}")
        if attempt < _MAX_RETRIES:
            time.sleep(delay)
            delay *= 2
    _debug_log(cfg, f"上报最终失败，丢弃 {len(spans)} spans")
    fallback_to_disk(cfg, spans)
    return False
