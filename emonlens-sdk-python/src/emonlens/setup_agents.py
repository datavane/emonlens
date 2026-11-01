"""`emonlens-codex-setup` / `emonlens-claude-setup` / `emonlens-zcode-setup` 一键接入命令。

    emonlens-codex-setup  --host http://emonlens:8300 --key tk-xxxx
    emonlens-claude-setup --host http://emonlens:8300 --key tk-xxxx
    emonlens-zcode-setup  --host http://emonlens:8300 --key tk-xxxx

各自完成：
    1. 写 ~/.emonlens/config.json（GUI Agent 读不到 shell 环境变量，配置文件兜底）
    2. 向对应 Agent 的 hooks 配置注册事件（安全追加/替换，幂等，保留已有钩子）

ZCode 使用独立的配置格式（hooks.enabled + hooks.events），并额外注册
PostToolUseFailure；matcher 一律省略（ZCode 的 matcher 是正则，`*` 非法）。
"""
import argparse
import json
import os
import shutil
import sys
import tempfile

HOOK_EVENTS = [("SessionStart", "session_start"),
               ("UserPromptSubmit", "user_prompt"),
               ("PreToolUse", "pre_tool_use"),
               ("PostToolUse", "post_tool_use"),
               ("Stop", "stop")]

# ZCode 额外支持 PostToolUseFailure（工具失败不触发 PostToolUse，需单独上报）
ZCODE_HOOK_EVENTS = HOOK_EVENTS + [("PostToolUseFailure", "post_tool_use_failure")]

TARGETS = {
    "codex": {
        "settings_path": os.path.expanduser("~/.codex/hooks.json"),
        "agent_key": "agent_codex",
        "default_agent": "codex-cli",
        "hooks_module": "emonlens.codex_hooks",
        "console": "emonlens-codex-hooks",
        "label": "Codex CLI",
        "format": "claude",
    },
    "claude": {
        "settings_path": os.path.expanduser("~/.claude/settings.json"),
        "agent_key": "agent_claude",
        "default_agent": "claude-code",
        "hooks_module": "emonlens.claude_hooks",
        "console": "emonlens-claude-hooks",
        "label": "Claude Code",
        "format": "claude",
    },
    "zcode": {
        "settings_path": os.path.expanduser("~/.zcode/cli/config.json"),
        "agent_key": "agent_zcode",
        "default_agent": "zcode",
        "hooks_module": "emonlens.zcode_hooks",
        "console": "emonlens-zcode-hooks",
        "label": "ZCode",
        "format": "zcode",
    },
}


def _hook_command(target_cfg) -> str:
    """钩子命令指向安装了本 SDK 的解释器（GUI Agent 不继承 PATH/venv）。"""
    found = shutil.which(target_cfg["console"])
    if found:
        return f'"{found}"' if " " in found else found
    exe = sys.executable
    module = target_cfg["hooks_module"]
    return f'"{exe}" -m {module}' if " " in exe else f"{exe} -m {module}"


def _load_json(path: str) -> dict:
    if os.path.exists(path):
        try:
            with open(path) as f:
                return json.load(f)
        except Exception:
            print(f"[warn] {path} 不是合法 JSON，将被覆盖（建议先备份）")
    return {}


def _atomic_write_json(path: str, data: dict, mode: int = None) -> None:
    """原子写：写到同目录临时文件再 replace，避免中途失败/并发写坏 Agent 配置。"""
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".emonlens-hooks-", dir=parent)
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())
        if mode is not None:
            os.chmod(tmp_path, mode)
        os.replace(tmp_path, path)
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def _insert_hook_entries(blocks: list, command: str, event: str, action: str,
                         matcher: bool) -> None:
    """向量组中追加/替换 EmonLens 钩子（保留其它工具的钩子，重复执行幂等）。"""
    command_line = f"{command} {action}"
    replaced = False
    for block in blocks:
        for h in block.get("hooks", []):
            if "emonlens" in str(h.get("command", "")):
                h["command"] = command_line
                replaced = True
    if not replaced:
        entry = {"hooks": [{"type": "command", "command": command_line,
                            "timeout": 10}]}
        if event == "PostToolUse" and matcher:
            entry["matcher"] = "*"
        blocks.append(entry)


def _register(hooks_path: str, command: str) -> None:
    """Claude Code / Codex 格式：{ "hooks": { "<Event>": [ {hooks:[...]} ] } }。"""
    hooks = _load_json(hooks_path) or {"hooks": {}}
    for event, action in HOOK_EVENTS:
        blocks = hooks.setdefault("hooks", {}).setdefault(event, [])
        _insert_hook_entries(blocks, command, event, action, matcher=True)
    _atomic_write_json(hooks_path, hooks)


def _register_zcode(config_path: str, command: str) -> None:
    """ZCode 格式：{ "hooks": { "enabled": true, "events": { "<Event>": [...] } } }。

    - 配置里的钩子默认禁用，必须置 hooks.enabled=true
    - matcher 是大小写敏感正则；`*` 是非法正则（永不匹配），因此一律省略
    - 顶层其它配置（plugins 等）原样保留
    """
    config = _load_json(config_path)
    hooks = config.setdefault("hooks", {})
    hooks["enabled"] = True
    events = hooks.setdefault("events", {})
    for event, action in ZCODE_HOOK_EVENTS:
        blocks = events.setdefault(event, [])
        _insert_hook_entries(blocks, command, event, action, matcher=False)
    existing_mode = None
    if os.path.exists(config_path):
        existing_mode = os.stat(config_path).st_mode & 0o777
    _atomic_write_json(config_path, config, mode=existing_mode)


def main(target: str = "codex", argv=None) -> None:
    tcfg = TARGETS[target]
    parser = argparse.ArgumentParser(
        prog=f"emonlens-{target}-setup",
        description=f"一键接入：注册 {tcfg['label']} hooks 到 EmonLens")
    parser.add_argument("--host", default=os.environ.get("EMONLENS_HOST", "http://localhost:8300"),
                        help="EmonLens 服务地址")
    parser.add_argument("--key", default=os.environ.get("EMONLENS_INGEST_KEY", ""),
                        help="Ingest Key（Agent 接入页签发；也可用 EMONLENS_INGEST_KEY 避免出现在命令历史/ps）")
    parser.add_argument("--agent", default=tcfg["default_agent"],
                        help=f"上报使用的 agentKey（默认 {tcfg['default_agent']}）")
    parser.add_argument("--settings-path", default=tcfg["settings_path"])
    parser.add_argument("--config-path", default=os.path.expanduser("~/.emonlens/config.json"))
    args = parser.parse_args(argv)
    if not args.key:
        parser.error("需要 --key 或环境变量 EMONLENS_INGEST_KEY")

    # 1) 写配置（GUI Agent 读不到 shell 环境变量，config.json 是主通道）
    config_path = os.path.expanduser(args.config_path)
    os.makedirs(os.path.dirname(config_path), exist_ok=True)
    try:
        os.chmod(os.path.dirname(config_path), 0o700)
    except OSError:
        pass
    cfg = {"host": args.host.rstrip("/"), "ingest_key": args.key,
           tcfg["agent_key"]: args.agent}
    if os.path.exists(config_path):
        try:
            with open(config_path) as f:
                existing = json.load(f)
            cfg = {**existing, **cfg}
        except Exception:
            pass
    # 配置文件含 ingest key：仅当前用户可读
    fd = os.open(config_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    try:
        os.chmod(config_path, 0o600)
    except OSError:
        pass

    # 2) 注册 hooks
    hooks_path = os.path.expanduser(args.settings_path)
    command = _hook_command(tcfg)
    if tcfg["format"] == "zcode":
        _register_zcode(hooks_path, command)
    else:
        _register(hooks_path, command)

    print(f"✅ {tcfg['label']} 已接入 EmonLens")
    print(f"   配置文件 : {config_path}")
    print(f"   hooks    : {hooks_path}")
    print(f"   钩子命令 : {command} <action>")
    print("")
    print(f"验证：正常使用 {tcfg['label']} 跑一次对话，然后在 EmonLens")
    print(f"「调用链路」筛选 Agent={args.agent} 查看自动上报的 Trace。")
    if target == "zcode":
        print("提示：ZCode 新开会话（或重启客户端）后加载新的 hooks 配置。")


def main_codex() -> None:
    main("codex")


def main_claude() -> None:
    main("claude")


def main_zcode() -> None:
    main("zcode")
