"""Claude Code hooks 桥接（与 Codex 同协议：SessionStart/UserPromptSubmit/PostToolUse/Stop）。

    pip install emonlens
    emonlens-claude-setup --host http://emonlens:8300 --key tk-xxxx
    # 注册到 ~/.claude/settings.json，之后正常使用 Claude Code 即可

事件增强：Stop 事件带 transcript_path 时，自动从会话记录提取最后一条助手消息作为根 output。
"""
import json
import sys

from .codex_hooks import handle_event, load_config


def _last_assistant_meta(transcript_path: str):
    """从 Claude Code 会话 JSONL 提取最后一条助手文本与模型名（尽力而为）。"""
    try:
        text = None
        model = None
        with open(transcript_path) as f:
            for line in f:
                try:
                    rec = json.loads(line)
                except Exception:
                    continue
                if rec.get("type") != "assistant":
                    continue
                msg = rec.get("message") or {}
                if msg.get("model"):
                    model = str(msg["model"])[:120]
                content = msg.get("content") or []
                parts = []
                for c in content if isinstance(content, list) else []:
                    if isinstance(c, dict) and c.get("type") == "text":
                        parts.append(c.get("text", ""))
                if parts:
                    text = "\n".join(parts)
        return (text or "")[:2000], model
    except Exception:
        return "", None


def main(argv=None) -> None:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        print("usage: emonlens-claude-hooks <session_start|user_prompt|post_tool_use|stop>",
              file=sys.stderr)
        return
    try:
        event = json.loads(sys.stdin.read() or "{}")
    except Exception:
        event = {}
    action = argv[0]
    if action == "stop":
        transcript = event.get("transcript_path")
        if transcript:
            text, model = _last_assistant_meta(transcript)
            if not event.get("output"):
                event["output"] = text
            if model and not event.get("model"):
                event["model"] = model
    try:
        cfg = load_config()
        handle_event(action, event,
                     agent_key=cfg.get("agent_claude") or "claude-code",
                     agent_version=cfg.get("agent_version_claude") or "claude-code",
                     trace_prefix="claude-", root_name="claude_session")
    except Exception as e:      # 观测桥接绝不影响 Claude Code
        print(f"[emonlens] hook failed: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
