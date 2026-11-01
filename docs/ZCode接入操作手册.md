# ZCode 接入 EmonLens 操作手册

> 目标：让 ZCode 自动把每轮会话上报到 EmonLens——根 span 记录提问与回答，每次工具调用一个 tool 子 span，每轮模型调用记录 token 与缓存命中。
> 适用：支持 `hooks.events` 配置的 ZCode（桌面端 / CLI）；macOS / Linux；Python ≥ 3.9。
> 预计耗时：3 分钟（pip 安装 + 一条命令）。

---

## 0. 前置条件

| 项 | 说明 |
| --- | --- |
| EmonLens 已运行 | 本机默认 `http://localhost:8300`；远程部署替换为实际地址 |
| Ingest Key | EmonLens「Agent 接入」→ Ingest Keys → 签发（形如 `tk-xxxx-xxxx-xxxx`，明文仅显示一次） |
| ZCode | 已安装并可正常使用（hooks 配置位于 `~/.zcode/cli/config.json`） |

---

## 1. 安装 SDK

```bash
pip install emonlens
# 无 PyPI 私服时可从仓库安装：
pip install <emonlens仓库>/emonlens-sdk-python
```

> 安装后获得两个命令：`emonlens-zcode-setup`（本手册 §2）与
> `emonlens-zcode-hooks`（钩子执行器，由注册的 hooks 调用，无需手动跑）。
>
> 若宿主 Python 受 PEP 668 管制（如 Homebrew Python），推荐 pipx：
> `pipx install emonlens`，或建专用 venv 后用其完整路径调用。

---

## 2. 一键注册 ZCode hooks

```bash
emonlens-zcode-setup --host http://你的EmonLens地址:8300 --key tk-你的IngestKey
```

该命令自动完成：

1. 写 `~/.emonlens/config.json`（钩子由 ZCode 子进程执行，读不到 shell
   环境变量，此文件是配置兜底；已存在的 host/key 会被更新，其它字段保留）
2. 在 `~/.zcode/cli/config.json` 中注册 6 个事件
   （SessionStart / UserPromptSubmit / PreToolUse / PostToolUse /
   PostToolUseFailure / Stop）：
   - 写入 `hooks.enabled = true`——ZCode 的配置文件钩子默认禁用，不置位不会执行
   - **安全追加**：保留已有钩子（例如其它 skill 的钩子）与顶层 `plugins` 配置
   - 重复执行幂等（不会注册两遍）
   - 不写 `matcher`：ZCode 的 matcher 是大小写敏感正则，`*` 是非法正则会永不匹配；
     省略表示匹配全部工具
3. 钩子命令指向安装了 SDK 的解释器绝对路径（GUI 启动的 ZCode 也能正确执行）

校验注册结果：

```bash
python3 -c "import json,os; d=json.load(open(os.path.expanduser('~/.zcode/cli/config.json'))); \
  print('enabled =', d['hooks'].get('enabled')); \
  [print(e, '→', [h['command'] for blk in d['hooks']['events'].get(e, []) for h in blk['hooks']]) \
   for e in ['SessionStart','UserPromptSubmit','PreToolUse','PostToolUse','PostToolUseFailure','Stop']]"
```

> **注册后请新开一个 ZCode 会话（或重启客户端）**，让新的 hooks 配置生效。

---

## 3. 端到端验证

### 3.1 离线模拟（不开 ZCode，直接验证链路）

依次模拟 6 个事件（`turnId` 用于把同一轮的工具/模型/根 span 归到一条 trace）：

```bash
SID="manual-test-$(date +%s)"
TURN="turn-manual-1"
echo "{\"session_id\":\"$SID\",\"source\":\"startup\",\"model\":\"provider/model-x\"}" \
  | emonlens-zcode-hooks session_start
echo "{\"session_id\":\"$SID\",\"turnId\":\"$TURN\",\"prompt\":\"帮我查询工单 #1024 的处理进度\"}" \
  | emonlens-zcode-hooks user_prompt
echo "{\"session_id\":\"$SID\",\"turnId\":\"$TURN\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"}}" \
  | emonlens-zcode-hooks pre_tool_use
sleep 0.3        # 模拟工具执行耗时，验证 PreToolUse/PostToolUse 配对
echo "{\"session_id\":\"$SID\",\"turnId\":\"$TURN\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"},\"tool_response\":\"a.py\"}" \
  | emonlens-zcode-hooks post_tool_use
echo "{\"session_id\":\"$SID\",\"turnId\":\"$TURN\",\"last_assistant_message\":\"工单 #1024 已进入二线处理\"}" \
  | emonlens-zcode-hooks stop
```

预期：命令全部静默成功（观测桥接失败也不报错，见 §6）。随后在 EmonLens
「调用链路」中应出现 `agentKey=zcode` 的 Trace：根 span（`zcode_session`，
input=提问、output=最终回答）+ tool 子 span（`Bash`，耗时为真实毫秒数——
若只发 `post_tool_use` 而不发 `pre_tool_use`，该工具 span 耗时会记 0ms）
+ LLM 轮次 span（`assistant_turn`）。

### 3.2 真实验证

新开一个 ZCode 会话，正常对话一次并让它使用工具，然后到 EmonLens
「调用链路」筛选 `Agent=zcode` 查看。

---

## 4. 在 EmonLens 中你将看到

| 位置 | 内容 |
| --- | --- |
| 调用链路 | `agentKey=zcode` 的 Trace：根 span（`zcode_session`）+ 每次工具调用一个 tool 子 span + 每轮 LLM span（`assistant_turn`） |
| 模型与 tokens | 模型名来自 ZCode 事件（SessionStart 的 `providerId/modelId`，或 Stop 时从 rollout 读取）；每轮 token 从 `~/.zcode/cli/rollout/model-io-<session_id>.jsonl` 聚合，**缓存命中量作为独立字段 `cachedInputTokens` 上报**，推理 tokens 在 span attributes |
| 会话 | 同一 ZCode 会话的多轮对话自动聚合（按 `session_id`），每轮各一条 trace（按 `turnId` 派生） |
| 用量成本 | 调用量与 tokens 按天统计（如需成本，为所用模型配置「模型价格」） |
| 事件告警 | 配合 VALIDATION 规则（如 `status == 'ERROR'`）监控 ZCode 工具失败（PostToolUseFailure 会上报 ERROR tool span） |

> **token 口径**：一轮内可能发生多次模型请求（工具调用循环），桥接按请求求和，
> 反映该轮实际计费输入/输出 tokens；`inputTokens` 含缓存命中部分，
> `cachedInputTokens` 是其中按缓存价计费的部分。若 ZCode 版本变更导致
> rollout 格式不可读，桥接会省略 tokens（而不是写 0 污染统计）。

> 用户洞察（可选）：ZCode 事件不含终端用户信息。如需按用户聚合，在
> `~/.emonlens/config.json` 加 `"user": "你的用户标识"`（或环境变量 `EMONLENS_USER`）。

---

## 5. 停止上报 / 卸载

```bash
# 彻底卸载：
pip uninstall emonlens
python3 -c "
import json, os
path = os.path.expanduser('~/.zcode/cli/config.json')
d = json.load(open(path))
events = d.get('hooks', {}).get('events', {})
for e in list(events):
    events[e] = [b for b in events[e]
                 if 'emonlens' not in json.dumps(b, ensure_ascii=False)]
json.dump(d, open(path, 'w'), ensure_ascii=False, indent=2)"
```

> 只删除 EmonLens 的 entry，保留其它钩子；`hooks.enabled` 维持原值即可。

---

## 6. 故障排查

| 现象 | 排查 |
| --- | --- |
| EmonLens 无 Trace | ① `~/.emonlens/config.json` 里的 `host`/`ingest_key` 是否正确（setup 写的，改这里即可）；② EmonLens 是否可达：`curl <host>:8300/actuator/health`；③ Key 是否 ACTIVE；④ `~/.zcode/cli/config.json` 里 `hooks.enabled` 是否为 `true`；⑤ 是否已新开会话/重启 ZCode |
| 钩子完全不触发 | ZCode 配置文件的钩子默认禁用：确认 `hooks.enabled: true`；确认事件名在支持的 7 个之内；确认命令里的解释器路径仍存在且能 `import emonlens` |
| 部分工具没有上报 | 工具失败时 ZCode 触发的是 `PostToolUseFailure`（已注册）；被权限拦截未执行的工具不会触发；只发 `PostToolUse` 而没有 `PreToolUse` 时耗时记 0ms |
| 没有模型/tokens | 模型名依赖 SessionStart 事件或 rollout 文件；tokens 依赖 `~/.zcode/cli/rollout/model-io-<session_id>.jsonl`（`EMONLENS_ZCODE_ROLLOUT_DIR` 可改目录） |
| 想看上报了什么 | 设 `EMONLENS_DEBUG=1`，桥接会把每次事件原文写入 `~/.emonlens/zcode/debug.log`（超 2MB 自动清空） |
| 上报失败影响 ZCode 吗 | 不会——桥接全量 try/except 静默；hooks timeout 10s，上报超时 5s，均在预算内 |

---

## 7. 桥接实现

桥接逻辑随 SDK 分发：`emonlens/zcode_hooks.py`（pip 安装后可直接
`python3 -m emonlens.zcode_hooks <action>` 调用）。核心流程：

- 配置：env `EMONLENS_INGEST_KEY/HOST` 优先，回退 `~/.emonlens/config.json`
- 状态：`~/.emonlens/zcode/<session_id>.json` 跨进程记录主轮次/提问/开始时间/待配对的工具调用；
  traceId 由 `turnId` 派生（`zcode-<session前12位>-<turnId哈希8位>`），同一轮的全部
  tool span（含子 agent 的 turn）与 Stop 的 LLM/根 span 因此恒落同一条 trace
- tokens：Stop 时回读 ZCode model_io rollout 尾部（最多 8MB），按 `turnId`
  聚合本轮全部模型请求的 usage；任一异常返回空，不影响上报
- 上报：urllib 直连 `/api/v1/ingest/events`，任何失败静默（不影响 ZCode）

服务端幂等：同一 `(traceId, spanId)` 重复上报会被跳过，同批其余数据正常落库
——重放/重试不会产生重复数据，也不会丢数据。

---

## 8. 数据契约速查

| 事件 | 上报内容 | 备注 |
| --- | --- | --- |
| `SessionStart` | 无上报，初始化状态文件（已有进行中轮次时不覆盖） | resume / compact 会重复触发 |
| `UserPromptSubmit` | 无上报，登记主轮次与提问 | 本轮 traceId 由该 `turnId` 派生 |
| `PreToolUse` | 无上报，记录工具开始时间 | 与 PostToolUse 配对算真实耗时 |
| `PostToolUse` | 1 条 tool 子 span（实时） | `toolName` / `tool_input` / `tool_response` |
| `PostToolUseFailure` | 1 条 ERROR tool 子 span | `error` 写入 `errorType` / `output` |
| `Stop` | 2 条 span：LLM 轮次 span（`assistant_turn`，含模型/回答/tokens）+ 根 span（`zcode_session`） | input=提问，output=最终回答；随后清理本轮状态 |

*手册版本 V1.0 · 已在 ZCode hooks 配置格式 + EmonLens 0.1.0 完成离线事件流验证；
真实 ZCode 会话在新开会话（或重启客户端）后自动上报*
