# Claude Code 接入 EmonLens 操作手册

> 目标：让任意开发者的 Claude Code 自动把每轮会话上报到 EmonLens——根 span 记录提问与回答（自动从会话记录提取），每次工具调用一个 tool 子 span。
> 适用：Claude Code（hooks 协议，与 Codex CLI 同源）；macOS / Linux；Python ≥ 3.9。
> 预计耗时：3 分钟（pip 安装 + 一条命令）。

---

## 0. 前置条件

| 项 | 说明 |
| --- | --- |
| EmonLens 已运行 | 本机默认 `http://localhost:8300`；远程部署替换为实际地址 |
| Ingest Key | EmonLens「Agent 接入」→ Ingest Keys → 签发（形如 `tk-xxxx-xxxx-xxxx`，明文仅显示一次） |
| Claude Code | `claude --version` 可执行 |

---

## 1. 安装 SDK（pip 方式）

```bash
pip install emonlens
# 无 PyPI 私服时可从仓库安装：
pip install <emonlens仓库>/emonlens-sdk-python
```

> 安装后获得两个命令：`emonlens-claude-setup`（本手册 §2）与
> `emonlens-claude-hooks`（钩子执行器，由注册的 hooks 调用，无需手动跑）。
>
> 若宿主 Python 受 PEP 668 管制（如 Homebrew Python），推荐 pipx：
> `pipx install emonlens`，或建专用 venv 后用其完整路径调用。

---

## 2. 一键注册 Claude Code hooks

```bash
emonlens-claude-setup --host http://你的EmonLens地址:8300 --key tk-你的IngestKey
```

该命令自动完成：

1. 写 `~/.emonlens/config.json`（钩子由 Claude Code 子进程执行，读不到 shell
   环境变量，此文件是配置兜底；已存在的 host/key 会被更新，其它字段保留）
2. 在 `~/.claude/settings.json` 的 `hooks` 中注册 5 个事件
   （SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / Stop）
   —— **安全追加**：保留已有钩子；重复执行幂等（不会注册两遍）
   （PreToolUse 与 PostToolUse 配对才能算出工具真实耗时，毫秒精度）
3. 钩子命令指向安装了 SDK 的解释器绝对路径（GUI 启动的 Claude Code 也能正确执行）

校验注册结果：

```bash
python3 -c "import json,os; d=json.load(open(os.path.expanduser('~/.claude/settings.json'))); \
  [print(e, '→', [h['command'] for blk in d['hooks'].get(e, []) for h in blk['hooks']]) \
   for e in ['SessionStart','UserPromptSubmit','PostToolUse','Stop']]"
```

> 首次触发时 Claude Code 可能提示信任新钩子，按提示确认即可。

---

## 3. 端到端验证

### 3.1 离线模拟（不打开 Claude Code，直接验证链路）

依次模拟 5 个事件（替换 `session_id` 为任意值）：

```bash
SID="manual-test-$(date +%s)"
echo "{\"session_id\":\"$SID\",\"prompt\":\"帮我查询工单 #1024 的处理进度\"}" \
  | emonlens-claude-hooks session_start
echo "{\"session_id\":\"$SID\",\"prompt\":\"帮我查询工单 #1024 的处理进度\"}" \
  | emonlens-claude-hooks user_prompt
echo "{\"session_id\":\"$SID\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"}}" \
  | emonlens-claude-hooks pre_tool_use
sleep 0.3        # 模拟工具执行耗时，验证 PreToolUse/PostToolUse 配对
echo "{\"session_id\":\"$SID\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"},\"tool_response\":\"a.py\"}" \
  | emonlens-claude-hooks post_tool_use
echo "{\"session_id\":\"$SID\"}" \
  | emonlens-claude-hooks stop
```

预期：命令全部静默成功（观测桥接失败也不报错，见 §6 排查）。随后在 EmonLens
「调用链路」中应出现 `agentKey=claude-code` 的 Trace：根 span（claude_session，
input=提问）+ 每个工具调用一个 tool 子 span（含工具名与参数，耗时为真实毫秒数——
若只发 `post_tool_use` 而不发 `pre_tool_use`，该工具 span 耗时会记 0ms）。

### 3.2 真实验证

正常使用 Claude Code 对话一次并让它使用工具（如让它读一个文件），然后到 EmonLens
「调用链路」筛选 `Agent=claude-code` 查看。根 span 的 output 会自动从
`~/.claude/projects/` 下的会话记录（transcript）提取最后一条助手消息。

---

## 4. 在 EmonLens 中你将看到

| 位置 | 内容 |
| --- | --- |
| 调用链路 | `agentKey=claude-code` 的 Trace：根 span（claude_session）+ 每次工具调用一个 tool 子 span |
| 会话 | 同一 Claude Code 会话的多轮对话自动聚合（按 session_id） |
| 用量成本 | 调用量按天统计（如需成本，为所用模型配置「模型价格」） |
| 事件告警 | 配合 VALIDATION 规则（如 `status == 'ERROR'`）监控工具失败 |

---

## 5. 停止上报 / 卸载

```bash
# 彻底卸载：
pip uninstall emonlens
```

从 `~/.claude/settings.json` 移除本工具注册的钩子（保留其它钩子）：

```bash
python3 -c "
import json, os
path = os.path.expanduser('~/.claude/settings.json')
d = json.load(open(path))
for e in ['SessionStart','UserPromptSubmit','PreToolUse','PostToolUse','Stop']:
    blocks = d.get('hooks', {}).get(e, [])
    d['hooks'][e] = [b for b in blocks if 'emonlens' not in json.dumps(b, ensure_ascii=False)]
json.dump(d, open(path,'w'), ensure_ascii=False, indent=2)"
```

---

## 6. 故障排查

| 现象 | 排查 |
| --- | --- |
| EmonLens 无 Trace | ① `~/.emonlens/config.json` 的 `host`/`ingest_key` 是否正确；② EmonLens 是否可达：`curl http://主机:8300/api/v1/auth/login`；③ Key 是否 ACTIVE（重新签发一个试） |
| Claude Code 提示钩子未信任 | 首次触发后按 Claude Code 提示确认 |
| 根 span 的 output 为空 | Stop 事件未带可读的 transcript_path，或会话记录中没有助手消息；手动检查 `~/.claude/projects/` 下对应 JSONL 是否存在 |
| 事件字段取不到（input/output 空） | Claude Code 版本字段名可能变化：桥接 `_pick()` 已做候选键兜底；把事件原文 dump 出来核对后补充键名 |
| 部分工具没有上报 | 仅 `PostToolUse` 事件产生 tool span；被权限拒绝未执行的工具（PermissionRequest 拒绝）不会触发 |
| 上报失败影响 Claude Code 吗 | 不会——桥接全量 try/except 静默；hooks timeout 10s，上报超时 5s，均在预算内 |

---

## 7. 桥接实现说明

桥接逻辑随 SDK 分发：`emonlens/claude_hooks.py`（pip 安装后可直接
`python3 -m emonlens.claude_hooks <action>` 调用）。核心流程：

- 配置：env `EMONLENS_INGEST_KEY/HOST` 优先，回退 `~/.emonlens/config.json`
- 事件处理：复用与 Codex 桥接同一套 `handle_event`（hooks 协议同源），
  agentKey / traceId 前缀 / 根 span 名按 Agent 区分（`claude-code` / `claude-` / `claude_session`）
- 状态：`~/.emonlens/codex/<session_id>.json` 跨进程记录 traceId/提问
  （该目录为 Codex 与 Claude 两个桥接共享的状态目录，按 session_id 命名互不冲突）
- **transcript 提取**：Stop 事件带 `transcript_path` 时，逐行解析会话 JSONL，
  取最后一条 `type=assistant` 消息的 text 作为根 span 的 output
- 上报：urllib 直连 `/api/v1/ingest/events`，任何失败静默（不影响 Claude Code）

服务端幂等：同一 `(traceId, spanId)` 重复上报会被跳过，同批其余数据正常落库
——重放/重试不会产生重复数据，也不会丢数据。

---

## 8. 数据契约速查

| 事件 | 上报内容 | 备注 |
| --- | --- | --- |
| `SessionStart` | 无上报，创建跨进程状态文件 | traceId = `claude-<session_id前12位>` |
| `UserPromptSubmit` | 更新根 span 的 input | 不产生请求 |
| `PostToolUse` | 1 条 tool 子 span（实时） | `tool_name` / `tool_input` / `tool_response` |
| `Stop` | 1 条根 span（agent） | input=提问，output=自动从 transcript 提取的最终回答，随后清理状态 |

---

## 9. 注册的钩子明细（setup 写入 settings.json 的内容）

```json
{
  "hooks": {
    "SessionStart":     [{ "hooks": [{ "type": "command",
        "command": "<SDK解释器路径> -m emonlens.claude_hooks session_start", "timeout": 10 }] }],
    "UserPromptSubmit": [{ "hooks": [{ "type": "command",
        "command": "<SDK解释器路径> -m emonlens.claude_hooks user_prompt", "timeout": 10 }] }],
    "PreToolUse":       [{ "hooks": [{ "type": "command",
        "command": "<SDK解释器路径> -m emonlens.claude_hooks pre_tool_use", "timeout": 10 }] }],
    "PostToolUse":      [{ "matcher": "*", "hooks": [{ "type": "command",
        "command": "<SDK解释器路径> -m emonlens.claude_hooks post_tool_use", "timeout": 10 }] }],
    "Stop":             [{ "hooks": [{ "type": "command",
        "command": "<SDK解释器路径> -m emonlens.claude_hooks stop", "timeout": 10 }] }]
  }
}
```

`<SDK解释器路径>` 由 setup 自动解析（pipx/venv 安装时指向对应解释器的绝对路径）。

---

*手册版本 V1.1（完整重写，修正 V1.0 串页问题）· 面向 Claude Code*
