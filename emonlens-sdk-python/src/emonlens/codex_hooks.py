"""Codex CLI hooks 桥接（pip 安装后由 console script 提供）。

    emonlens-codex-hooks session_start   # 在 ~/.codex/hooks.json 中注册使用

配置来源（优先级）：
    1. 环境变量 EMONLENS_INGEST_KEY / EMONLENS_HOST
    2. ~/.emonlens/config.json（由 `emonlens-codex-setup` 写入）
       —— GUI 版 Codex 读不到 shell 环境变量，因此 config.json 是主通道。
"""
import hashlib
import json
import os
import sys
import time
import urllib.request

def _state_dir() -> str:
    return os.environ.get("EMONLENS_STATE_DIR",
                          os.path.expanduser("~/.emonlens/codex"))


def _config_path() -> str:
    return os.environ.get("EMONLENS_CONFIG",
                          os.path.expanduser("~/.emonlens/config.json"))


def load_config() -> dict:
    cfg = {}
    try:
        with open(_config_path()) as f:
            cfg = json.load(f)
    except Exception:
        pass
    host = os.environ.get("EMONLENS_HOST") or cfg.get("host") or "http://localhost:8300"
    env_agent = os.environ.get("EMONLENS_AGENT") or ""
    env_version = os.environ.get("EMONLENS_AGENT_VERSION") or ""
    return {
        "key": os.environ.get("EMONLENS_INGEST_KEY") or cfg.get("ingest_key") or "",
        "url": host.rstrip("/") + "/api/v1/ingest/events",
        "agent": env_agent or cfg.get("agent") or "codex-cli",
        "version": env_version or cfg.get("agent_version") or "codex-cli",
        # setup 写入的是 agent_codex / agent_claude / agent_zcode；分开返回，避免多个 hook 互相覆盖
        "agent_codex": env_agent or cfg.get("agent_codex") or cfg.get("agent") or "codex-cli",
        "agent_claude": env_agent or cfg.get("agent_claude") or cfg.get("agent") or "claude-code",
        "agent_zcode": env_agent or cfg.get("agent_zcode") or cfg.get("agent") or "zcode",
        "agent_version_codex": env_version or cfg.get("agent_version_codex") or "codex-cli",
        "agent_version_claude": env_version or cfg.get("agent_version_claude") or "claude-code",
        "agent_version_zcode": env_version or cfg.get("agent_version_zcode") or "zcode",
        # 可选：终端用户标识（~/.emonlens/config.json 加 "user": "xxx"），
        # 供「用户洞察」聚合；Codex/Claude 事件本身不含用户信息
        "user": os.environ.get("EMONLENS_USER") or cfg.get("user") or "",
    }


def _iso(t: float) -> str:
    # 毫秒精度：服务端用 startTime/endTime 重算 durationMs（忽略客户端传的 durationMs），
    # 截断到整秒会让所有 <1s 的工具调用显示 0ms、1.5s 显示 1000ms。
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(t)) + ".%03d" % int((t % 1) * 1000)


def _post(cfg, spans) -> None:
    req = urllib.request.Request(
        cfg["url"], data=json.dumps(spans, ensure_ascii=False).encode("utf-8"),
        headers={"X-Trust-Key": cfg["key"], "Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=5).read()


def _with_grouping(span: dict, sid: str, user: str = "") -> dict:
    """补会话/用户归属：sessionId 缺失时服务端无法聚合会话（会话页恒空）。"""
    if sid:
        span["sessionId"] = sid
    if user:
        span["userId"] = user
    return span


def _state_path(sid: str) -> str:
    return os.path.join(_state_dir(), (sid or "default").replace("/", "_") + ".json")


def _load_state(sid: str):
    try:
        with open(_state_path(sid)) as f:
            return json.load(f)
    except Exception:
        return None


def _save_state(sid: str, state: dict) -> None:
    os.makedirs(_state_dir(), exist_ok=True)
    with open(_state_path(sid), "w") as f:
        json.dump(state, f, ensure_ascii=False)


def _pick(event: dict, *keys):
    """防御性取值：Codex 事件字段名可能随版本变化，依次尝试常见键。"""
    for k in keys:
        v = event.get(k)
        if v:
            return v
    return None


def _turn_id(event: dict) -> str:
    """Codex 实际字段为 turn_id（下划线）；兼容连字符/驼峰变体。"""
    return str(_pick(event, "turn_id", "turn-id", "turnId") or "")


def _stable_span_id(seed: str) -> str:
    """由稳定键（turn_id）派生 12 位 spanId：stop 重复触发时服务端按 (span_id, trace_id) 幂等去重。"""
    return hashlib.md5(seed.encode("utf-8")).hexdigest()[:12] if seed else os.urandom(4).hex()


def _turn_trace(trace_prefix: str, sid: str, turn_id: str) -> str:
    """按 (session, turn) 派生稳定 traceId。

    Codex 除 SessionStart 外所有事件都带 turn_id；用它派生可保证同一轮次的
    tool / llm / root span 恒落同一条 trace，不受 state 文件被覆盖影响：
      - SessionStart 在 resume / compact 时会再次触发（旧实现会整体覆盖 state.traceId）
      - 主 agent 与子 agent 共享同一 session 的 state（UserPromptSubmit 事件无 agent_id）

    turn_id 为空（老版本 Codex）时退化为时间后缀，仍与 state 记录保持一致。
    """
    sid_part = sid[:12] if sid and sid != "default" else "anon"
    if turn_id:
        return trace_prefix + sid_part + "-" + hashlib.md5(str(turn_id).encode("utf-8")).hexdigest()[:8]
    return trace_prefix + sid_part + "-" + str(int(time.time() * 1000) % 100000000)


def _turn_of_event(state: dict, event: dict) -> str:
    """分组用 turn_id：优先主轮次（仅 UserPromptSubmit 写入 state.turnId）。

    子 agent 的工具事件携带的是子 agent 自己的 turn_id，若按它分组会拆出
    永远没有 llm/root 的孤儿 trace；挂到主轮次后，stop 的 llm/root 与全部
    tool span 共处一条 trace（多 agent 并发也如此）。尚无主轮次（中途注册
    hooks / 事件本身不带 turn_id，如 Claude Code 桥接）时返回空串，由调用方
    回退到 state 里当前 traceId，避免同 session 内每个 span 自成一条 trace。
    """
    return str(state.get("turnId") or "") or _turn_id(event)


def _trace_for(state: dict, event: dict, trace_prefix: str, sid: str) -> str:
    """本条 span 应落入的 traceId。

    有轮次信息（Codex）→ 按 (session, turn) 派生，与同轮 user_prompt / stop 恒一致；
    无轮次信息（Claude Code 事件不带 turn_id）→ 沿用 state 里的当前 traceId，
    让同一条 user_prompt 之后的 tool 与 stop 的 llm/root 落在同一条 trace。
    """
    turn_key = _turn_of_event(state, event)
    if turn_key:
        return _turn_trace(trace_prefix, sid, turn_key)
    return str(state.get("traceId") or "")


def _read_last_usage(transcript_path: str):
    """从 Codex rollout jsonl 尾部读取最后一个 token_count 事件的 last_token_usage（本轮轮次增量）。
    rollout 可达数十 MB：只回读文件尾部（最多 3×512KB）。任何异常返回 None，观测绝不影响 Codex。"""
    if not transcript_path:
        return None
    try:
        with open(transcript_path, "rb") as f:
            f.seek(0, os.SEEK_END)
            pos = f.tell()
            data = b""
            for _ in range(3):
                step = min(512 * 1024, pos)
                if step <= 0:
                    break
                pos -= step
                f.seek(pos)
                data = f.read(step) + data
                lines = data.split(b"\n")
                candidates = lines if pos == 0 else lines[:-1]   # 尾部行可能被截断
                for raw in reversed(candidates):
                    if b"token_count" not in raw:
                        continue
                    try:
                        d = json.loads(raw)
                    except Exception:
                        continue
                    payload = d.get("payload") or {}
                    if payload.get("type") != "token_count":
                        continue
                    info = payload.get("info") or {}
                    usage = info.get("last_token_usage") or info.get("total_token_usage")
                    if usage:
                        return usage
                if pos == 0:
                    break
    except Exception:
        return None
    return None


def handle_event(action: str, event: dict, agent_key: str = "codex-cli",
                 agent_version: str = "codex-cli", trace_prefix: str = "codex-",
                 root_name: str = "codex_session", turn_usage: dict = None) -> None:
    """处理一次 hook 事件。

    turn_usage：调用方已解析好的本轮 token 用量（OpenAI 字段名）；为空时
    回退到从 event.transcript_path（Codex rollout）读取。
    """
    cfg = load_config()
    if not cfg["key"]:
        return
    if agent_key == "codex-cli":
        agent_key = cfg.get("agent_codex", agent_key)
    if agent_version == "codex-cli":
        agent_version = cfg.get("agent_version_codex", agent_version)
    sid = (_pick(event, "session_id", "sessionId", "thread-id", "thread_id")
           or event.get("turn-id") or "default")
    state = _load_state(sid) or {}
    now = time.time()

    if action == "session_start":
        # resume / compact 会再次触发 SessionStart：进行中的轮次状态不可覆盖，
        # 否则后续 stop 的 llm/root span 会落到另一个 traceId（Trace 碎片化）
        if not state:
            state = {"traceId": "", "startTs": now, "prompt": "", "prompts": {},
                     "turnStart": {}, "turnId": "",
                     "model": _pick(event, "model", "model_name") or ""}
            _save_state(sid, state)
        elif event.get("model") and not state.get("model"):
            state["model"] = str(event["model"])[:120]
            _save_state(sid, state)
        return

    if action == "user_prompt":
        # 每轮 UserPromptSubmit 开新 trace（traceId 由 turn_id 派生，天然每轮唯一）：
        # 同一 session 的多轮对话各成一条 trace，避免 root span（spanId 固定）被后端幂等去重
        prompt = str(_pick(event, "prompt", "user_prompt", "message") or "")[:2000]
        turn_id = _turn_id(event)
        prompts = state.get("prompts") if isinstance(state.get("prompts"), dict) else {}
        turn_start = state.get("turnStart") if isinstance(state.get("turnStart"), dict) else {}
        if turn_id:
            prompts[turn_id] = prompt
            turn_start[turn_id] = now
            while len(prompts) > 8:             # 只保留最近 8 轮，防 state 文件无限增长
                prompts.pop(next(iter(prompts)))
            while len(turn_start) > 8:
                turn_start.pop(next(iter(turn_start)))
        state = {"traceId": _turn_trace(trace_prefix, sid, turn_id), "startTs": now,
                 "prompt": prompt, "prompts": prompts, "turnStart": turn_start,
                 "turnId": turn_id,
                 "model": _pick(event, "model", "model_name") or state.get("model") or "",
                 "pending": []}
        _save_state(sid, state)
        return

    if action == "pre_tool_use":
        # 记录工具开始时间（PostToolUse 时配对计算真实耗时）
        if not state.get("traceId"):
            state = {"traceId": _turn_trace(trace_prefix, sid, _turn_id(event)),
                     "startTs": now, "prompt": "", "turnId": "",
                     "model": _pick(event, "model", "model_name") or ""}
            _save_state(sid, state)
        if event.get("model") and not state.get("model"):
            state["model"] = str(event["model"])[:120]
            _save_state(sid, state)
        tool = str(_pick(event, "tool_name", "toolName") or "tool")[:120]
        pending = state.setdefault("pending", [])
        pending.append({"tool": tool, "ts": now})
        state["pending"] = pending[-20:]          # 防未配对堆积
        _save_state(sid, state)
        return

    if action in ("post_tool_use", "post_tool_use_failure"):
        if not state.get("traceId"):
            state = {"traceId": _turn_trace(trace_prefix, sid, _turn_id(event)),
                     "startTs": now, "prompt": "", "turnId": "",
                     "model": _pick(event, "model", "model_name") or ""}
            _save_state(sid, state)
        model = _pick(event, "model", "model_name") or state.get("model") or ""
        if model and not state.get("model"):
            state["model"] = str(model)[:120]
        _save_state(sid, state)
        # 挂到主轮次（state.turnId，仅 prompt 写入）：子 agent 工具与主轮次 stop 的 llm/root 同 trace；
        # 无轮次信息（Claude Code 事件不带 turn_id）时沿用 state 的当前 traceId，不新开
        trace_id = _trace_for(state, event, trace_prefix, sid)
        if not trace_id:
            return
        tool = str(_pick(event, "tool_name", "toolName") or "tool")[:120]
        # 配对最近的同名 PreToolUse；无记录则 0ms（旧钩子/中途注册场景）
        pending = state.get("pending", [])
        start_ts = None
        for i in range(len(pending) - 1, -1, -1):
            if pending[i].get("tool") == tool:
                start_ts = pending.pop(i)["ts"]
                break
        if start_ts is None and pending:
            start_ts = pending.pop(0)["ts"]
        state["pending"] = pending
        _save_state(sid, state)

        tin = _pick(event, "tool_input", "toolInput", "input") or ""
        tout = _pick(event, "tool_response", "toolResponse", "output") or ""
        error = _pick(event, "error", "is_error")
        if action == "post_tool_use_failure":
            # ZCode PostToolUseFailure 不触发 PostToolUse：error 是消息文本，
            # error_details 是结构化对象；没有响应体时用错误信息充当 output。
            error = error or "tool_failed"
            if not tout:
                tout = _pick(event, "error", "error_details") or ""
        span = {"traceId": trace_id, "spanId": os.urandom(4).hex(),
                "parentSpanId": "root",
                "agentKey": agent_key, "agentVersion": agent_version,
                "spanType": "tool", "spanName": tool,
                "toolName": tool,
                "startTime": _iso(start_ts) if start_ts else _iso(now),
                "endTime": _iso(now),
                "durationMs": int((now - start_ts) * 1000) if start_ts else 0,
                "input": (json.dumps(tin, ensure_ascii=False, default=str)[:2000]
                          if not isinstance(tin, str) else str(tin)[:2000]),
                "output": str(tout)[:2000],
                "status": "ERROR" if error else "OK"}
        if model:
            span["modelName"] = str(model)[:120]
        attrs = {}
        turn_id = _turn_id(event)
        if turn_id:
            attrs["turnId"] = turn_id
        cwd = _pick(event, "cwd")
        if cwd:
            attrs["cwd"] = str(cwd)[:300]
        if attrs:
            span["attributes"] = attrs
        if error:
            span["errorType"] = str(error)[:120]
        _post(cfg, [_with_grouping(span, sid, cfg.get("user", ""))])
        return

    if action == "stop":
        # 分组键与 tool span 一致（主轮次优先）：即使 state 丢失/被覆盖，
        # llm/root span 仍与本轮（含子 agent）全部 tool span 落在同一条 trace
        event_turn = _turn_id(event)
        turn_id = _turn_of_event(state, event)
        trace_id = _trace_for(state, event, trace_prefix, sid)
        if not trace_id:
            return
        prompts = state.get("prompts") if isinstance(state.get("prompts"), dict) else {}
        turn_start = state.get("turnStart") if isinstance(state.get("turnStart"), dict) else {}
        output = _pick(event, "last_assistant_message", "last-assistant-message",
                       "assistant_message", "response", "output") or ""
        model = _pick(event, "model", "model_name") or state.get("model") or None
        prompt = str(prompts.get(turn_id) or prompts.get(event_turn)
                     or state.get("prompt") or "")[:2000]
        start_ts = turn_start.get(turn_id) or turn_start.get(event_turn) or state.get("startTs") or now
        # 本轮 tokens：调用方预解析的用量优先；否则从 Codex rollout 取 last_token_usage
        usage = (turn_usage if turn_usage is not None
                 else _read_last_usage(_pick(event, "transcript_path", "transcriptPath")))
        # LLM 轮次 span：模型名 + 本轮提示词/回答 + tokens，供列表摘要与 LLM 维度分析
        spans = []
        if output or prompt:
            llm_span = {"traceId": trace_id, "spanId": _stable_span_id(turn_id),
                        "parentSpanId": "root",
                        "agentKey": agent_key, "agentVersion": agent_version,
                        "spanType": "llm", "spanName": "assistant_turn",
                        "startTime": _iso(start_ts),
                        "endTime": _iso(now),
                        "input": prompt, "output": str(output)[:2000],
                        "status": "OK"}
            if model:
                llm_span["modelName"] = str(model)[:120]
            if usage:
                tin = int(usage.get("input_tokens") or 0)
                tout = int(usage.get("output_tokens") or 0)
                if tin or tout:
                    llm_span["inputTokens"] = tin
                    llm_span["outputTokens"] = tout
                if usage.get("cached_input_tokens") is not None:
                    llm_span["cachedInputTokens"] = int(usage.get("cached_input_tokens") or 0)
                attrs = {}
                if usage.get("reasoning_output_tokens") is not None:
                    attrs["reasoningOutputTokens"] = int(usage.get("reasoning_output_tokens") or 0)
                if usage.get("total_tokens") is not None:
                    attrs["turnTotalTokens"] = int(usage.get("total_tokens") or 0)
                if attrs:
                    llm_span["attributes"] = attrs
            spans.append(llm_span)
        root = {"traceId": trace_id, "spanId": "root", "parentSpanId": None,
                "agentKey": agent_key, "agentVersion": agent_version,
                "spanType": "agent", "spanName": root_name,
                "startTime": _iso(start_ts),
                "endTime": _iso(now),
                "input": prompt,
                "output": str(output)[:2000], "status": "OK"}
        if model:
            root["modelName"] = str(model)[:120]
        if turn_id:
            root["attributes"] = {"turnId": turn_id}
        spans.append(root)
        spans = [_with_grouping(s, sid, cfg.get("user", "")) for s in spans]
        _post(cfg, spans)
        # 本轮状态出清；仍有其它进行中的轮次（主/子 agent 交错）则保留 state
        for key in (turn_id, event_turn):
            prompts.pop(key, None)
            turn_start.pop(key, None)
        if prompts or turn_start:
            state = state if isinstance(state, dict) else {}
            state["prompts"] = prompts
            state["turnStart"] = turn_start
            _save_state(sid, state)
        else:
            try:
                os.remove(_state_path(sid))
            except Exception:
                pass


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


def main(argv=None) -> None:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        print("usage: emonlens-codex-hooks <session_start|user_prompt|post_tool_use|stop>",
              file=sys.stderr)
        return
    try:
        event = json.loads(sys.stdin.read() or "{}")
    except Exception:
        event = {}
    _debug_log(argv[0], event)
    try:
        cfg = load_config()
        handle_event(argv[0], event,
                     agent_key=cfg.get("agent_codex", "codex-cli"),
                     agent_version=cfg.get("agent_version_codex", "codex-cli"))
    except Exception as e:      # 观测桥接绝不影响 Codex
        print(f"[emonlens] hook failed: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
