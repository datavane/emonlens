"""ZCode hooks 桥接（pip 安装后由 console script 提供）。

    emonlens-zcode-setup --host http://emonlens:8300 --key tk-xxxx
    # 注册到 ~/.zcode/cli/config.json 的 hooks.events，之后正常使用 ZCode 即可

与 Claude Code 的差异：
    - 配置结构是 hooks.events.<Event>，且必须显式 hooks.enabled=true
    - stdin 事件字段兼容 Claude Code，并原生携带 turnId：同一轮的全部
      tool / llm / root span 自动落入同一条 trace
    - Stop 直接携带 last_assistant_message，无需解析 transcript
    - token 用量可从 ~/.zcode/cli/rollout/model-io-<session_id>.jsonl 聚合
"""
import glob
import json
import os
import sys
import time

from .codex_hooks import _pick, _turn_id, handle_event, load_config

# 单行 model_io 可能包含完整请求体（数百 KB）；只回读尾部，避免 Stop 钩子变慢
_ROLLOUT_TAIL_BYTES = 8 * 1024 * 1024


def _state_dir() -> str:
    return os.environ.get("EMONLENS_STATE_DIR",
                          os.path.expanduser("~/.emonlens/zcode"))


def _debug_log(action: str, event: dict) -> None:
    """调试日志：仅 EMONLENS_DEBUG 开启时记录（含提示词/工具原文，默认不落盘）。"""
    if os.environ.get("EMONLENS_DEBUG", "").lower() not in ("1", "true", "yes"):
        return
    try:
        path = os.path.join(_state_dir(), "debug.log")
        if os.path.exists(path) and os.path.getsize(path) > 2 * 1024 * 1024:
            os.remove(path)
        os.makedirs(_state_dir(), exist_ok=True)
        with open(path, "a") as f:
            f.write(time.strftime("%H:%M:%S ") + action + " " +
                    json.dumps(event, ensure_ascii=False)[:1500] + "\n")
    except Exception:
        pass


def _rollout_path(sid: str):
    if not sid:
        return None
    directory = os.environ.get("EMONLENS_ZCODE_ROLLOUT_DIR",
                               os.path.expanduser("~/.zcode/cli/rollout"))
    direct = os.path.join(directory, "model-io-" + str(sid).replace("/", "_") + ".jsonl")
    if os.path.exists(direct):
        return direct
    # session_id 前缀在不同版本可能是 session_xxx / sess_xxx / 纯 uuid
    matches = glob.glob(os.path.join(directory, "model-io-*" + str(sid) + ".jsonl"))
    return matches[0] if len(matches) == 1 else None


def _model_of(record: dict):
    model = record.get("model") or {}
    mid = (record.get("response") or {}).get("modelId") or model.get("modelId")
    if not mid:
        return None
    provider = model.get("providerId")
    return f"{provider}/{mid}" if provider else str(mid)


def _read_turn_usage(sid: str, turn_id: str):
    """聚合本轮主 turn 的全部 model_io 调用（每次请求独立计费，按请求求和）。

    返回 (usage, model)。usage 使用服务端认识的 OpenAI 字段名：
    input_tokens 含缓存命中部分，cached_input_tokens 为其中命中缓存的量。
    文件很大时只读尾部；任何异常返回 (None, None)，观测绝不影响 ZCode。
    """
    path = _rollout_path(sid)
    if not path or not turn_id:
        return None, None
    try:
        size = os.path.getsize(path)
        start = max(0, size - _ROLLOUT_TAIL_BYTES)
        with open(path, "rb") as f:
            f.seek(start)
            data = f.read()
        lines = data.split(b"\n")
        if start:                       # 首行可能从中间截断
            lines = lines[1:]
    except Exception:
        return None, None

    usage = {"input_tokens": 0, "output_tokens": 0,
             "cached_input_tokens": 0, "reasoning_output_tokens": 0,
             "total_tokens": 0}
    model = None
    seen = False
    for raw in lines:
        if not raw.strip():
            continue
        try:
            record = json.loads(raw)
        except Exception:
            continue
        if str(record.get("turnId") or "") != str(turn_id):
            continue
        model = _model_of(record) or model
        u = (record.get("response") or {}).get("usage") or {}
        try:
            tin = int(u.get("inputTokens") or 0)
            tout = int(u.get("outputTokens") or 0)
            cached = int(u.get("cacheReadTokens") or 0)
            reasoning = int(u.get("reasoningTokens") or 0)
            total = int(u.get("totalTokens") or 0)
        except (TypeError, ValueError):
            continue
        if not (tin or tout):           # 失败的请求没有 usage：跳过，不写 0 污染统计
            continue
        seen = True
        usage["input_tokens"] += tin
        usage["output_tokens"] += tout
        usage["cached_input_tokens"] += cached
        usage["reasoning_output_tokens"] += reasoning
        usage["total_tokens"] += total or (tin + tout)
    return (usage if seen else None), model


def main(argv=None) -> None:
    # 跨进程状态与 Codex 桥接隔离（用户显式设置 EMONLENS_STATE_DIR 时以其为准）
    os.environ.setdefault("EMONLENS_STATE_DIR", os.path.expanduser("~/.emonlens/zcode"))
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        print("usage: emonlens-zcode-hooks <session_start|user_prompt|pre_tool_use|"
              "post_tool_use|post_tool_use_failure|stop>", file=sys.stderr)
        return
    try:
        event = json.loads(sys.stdin.read() or "{}")
    except Exception:
        event = {}
    action = argv[0]
    _debug_log(action, event)
    usage = None
    try:
        cfg = load_config()
        if not cfg["key"]:
            return
        if action == "stop":
            sid = str(_pick(event, "session_id", "sessionId") or "")
            turn_id = _turn_id(event)
            usage, model = _read_turn_usage(sid, turn_id)
            if model and not event.get("model"):
                event["model"] = model
        handle_event(action, event,
                     agent_key=cfg.get("agent_zcode") or "zcode",
                     agent_version=cfg.get("agent_version_zcode") or "zcode",
                     trace_prefix="zcode-", root_name="zcode_session",
                     turn_usage=usage)
    except Exception as e:      # 观测桥接绝不影响 ZCode
        print(f"[emonlens] hook failed: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
