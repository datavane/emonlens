# Codex CLI 接入 EmonLens 操作手册

> 目标：让任意开发者的 Codex CLI 自动把每轮会话上报到 EmonLens——根 span 记录提问与回答，每次工具调用一个 tool 子 span。
> 适用：Codex CLI ≥ 0.140（hooks 机制，已在 0.142.5 验证）；macOS / Linux；Python ≥ 3.9。
> 预计耗时：3 分钟（pip 安装 + 一条命令）。

## 0. 前置条件

| 项 | 说明 |
| --- | --- |
| EmonLens 已运行 | 本机默认 `http://localhost:8300`；远程部署替换为实际地址 |
| Ingest Key | EmonLens「Agent 接入」→ Ingest Keys → 签发（形如 `tk-xxxx-xxxx-xxxx`，明文仅显示一次） |
| Codex CLI | `codex --version` 可执行 |

---

## 0. 前置条件

| 项 | 说明 |
| --- | --- |
| EmonLens 已运行 | 本机默认 `http://localhost:8300`；远程部署替换为实际地址 |
| Ingest Key | EmonLens「Agent 接入」→ Ingest Keys → 签发（形如 `tk-xxxx-xxxx-xxxx`，明文仅显示一次） |
| Codex CLI | `codex --version` 可执行 |

---

## 1. 安装 SDK（pip 方式，无需手动拷贝文件）

```bash
pip install emonlens
# 无 PyPI 私服时可从仓库安装：
pip install <emonlens仓库>/emonlens-sdk-python
# 或发布 wheel：pip install emonlens-0.1.0-py3-none-any.whl
```

> GUI 版 Codex 读不到 shell 环境变量——下一步的 setup 命令会把配置写入
> `~/.emonlens/config.json` 作为兜底，因此无需在 Codex 进程里设置环境变量。
>
> 若宿主 Python 受 PEP 668 管制（如 Homebrew Python），推荐 pipx：
> `pipx install emonlens`，或建专用 venv 后用其完整路径调用。

---

## 2. 一键注册 Codex hooks

```bash
emonlens-codex-setup --host http://你的EmonLens地址:8300 --key tk-你的IngestKey
```

该命令自动完成：

1. 写 `~/.emonlens/config.json`（桥接钩子的配置兜底）
2. 在 `~/.codex/hooks.json` 注册 5 个事件（SessionStart / UserPromptSubmit / PreToolUse /
   PostToolUse / Stop）—— **安全追加**：保留已有钩子；重复执行幂等（不会注册两遍）
   （PreToolUse 与 PostToolUse 配对才能算出工具的真实耗时，毫秒精度）
3. 钩子命令指向安装了 SDK 的解释器绝对路径（GUI 版 Codex 也能正确执行）

校验注册结果：

```bash
python3 -c "import json,os; d=json.load(open(os.path.expanduser('~/.codex/hooks.json'))); \
  [print(e, '→', [h['command'] for blk in d['hooks'].get(e, []) for h in blk['hooks']]) \
   for e in ['SessionStart','UserPromptSubmit','PostToolUse','Stop']]"
```

> 首次触发时 Codex 可能提示信任新钩子（hooks.state 记录 trusted_hash），按提示确认即可。

---

## 3. 端到端验证

### 3.1 离线模拟（不打开 Codex，直接验证链路）

依次模拟 5 个事件（替换 `session_id` 为任意值）：

```bash
SID="manual-test-$(date +%s)"
echo "{\"session_id\":\"$SID\",\"prompt\":\"帮我查询工单 #1024 的处理进度\"}" \
  | emonlens-codex-hooks session_start
echo "{\"session_id\":\"$SID\",\"prompt\":\"帮我查询工单 #1024 的处理进度\"}" \
  | emonlens-codex-hooks user_prompt
echo "{\"session_id\":\"$SID\",\"tool_name\":\"query_credit_balance\",\"tool_input\":{\"customerId\":\"12345\"}}" \
  | emonlens-codex-hooks pre_tool_use
sleep 0.3        # 模拟工具执行耗时，验证 PreToolUse/PostToolUse 配对
echo "{\"session_id\":\"$SID\",\"tool_name\":\"query_credit_balance\",\"tool_input\":{\"customerId\":\"12345\"},\"tool_response\":{\"balance\":50000}}" \
  | emonlens-codex-hooks post_tool_use
echo "{\"session_id\":\"$SID\",\"last_assistant_message\":\"工单 #1024 已进入二线处理\"}" \
  | emonlens-codex-hooks stop
```

预期：命令全部静默成功（观测桥接失败也不报错，见 §6 排查）。随后在 EmonLens
「调用链路」中应出现 `agentKey=codex-cli` 的 Trace：根 span（codex_session，input=提问）
+ tool 子 span（query_credit_balance，含工具参数，耗时为真实毫秒数——若只发
`post_tool_use` 而不发 `pre_tool_use`，该工具 span 耗时会记 0ms）。

### 3.2 真实验证

重启 Codex（或新开会话），正常对话一次并让它使用工具，然后到 EmonLens
「调用链路」筛选 `Agent=codex-cli` 查看。

---

## 4. 在 EmonLens 中你将看到

| 位置 | 内容 |
| --- | --- |
| 调用链路 | `agentKey=codex-cli` 的 Trace：根 span（codex_session）+ 每次工具调用一个 tool 子 span + 每轮 LLM span（assistant_turn） |
| 模型与 tokens | 每个 span 携带 Codex 事件中的 `model`（模型列/模型用量可见）；每轮结束（Stop）从 rollout 读取 `last_token_usage`，LLM span 记录输入/输出 tokens，**缓存命中量作为独立字段 `cachedInputTokens` 上报**（Trace 详情与会话汇总均可见；推理 tokens 在 span attributes） |
| 会话 | 同一 Codex 会话的多轮对话自动聚合（按 session_id） |
| 用量成本 | 调用量与 tokens 按天统计（如需成本，为所用模型配置「模型价格」） |
| 事件告警 | 配合 VALIDATION 规则（如 `status == 'ERROR'`）监控 Codex 工具失败 |

> 用户洞察（可选）：Codex 事件不含终端用户信息。如需按用户聚合，在
> `~/.emonlens/config.json` 加 `"user": "你的用户标识"`（或环境变量 `EMONLENS_USER`）。

> 说明：每轮对话的新 trace 在轮次进行中为 RUNNING（工具调用实时上报），Stop 时补
> LLM span（含模型/回答/tokens）与根 span 后收口为 COMPLETED；若轮次被强制中断，
> 服务端 idle 超时（默认 15 分钟无新 span）也会收口，但该轮 tokens 不落库。

---

## 5. 停止上报 / 卸载

```bash
# 临时停止：从 hooks.json 移除 EmonLens 的 entry（保留其它钩子），之后可重新执行 setup 恢复
# 彻底卸载：
pip uninstall emonlens
python3 -c "
import json, os
path = os.path.expanduser('~/.codex/hooks.json')
d = json.load(open(path))
# 匹配 'codex_hooks' 而非 'codex_hooks.py'：SDK setup 注册的是
# 'python -m emonlens.codex_hooks <action>'（无 .py），早期单文件副本才是
# '.../codex_hooks.py <action>'；只匹配 .py 会一条都删不掉
for e in ['SessionStart','UserPromptSubmit','PreToolUse','PostToolUse','Stop']:
    blocks = d['hooks'].get(e, [])
    d['hooks'][e] = [b for b in blocks
                     if 'codex_hooks' not in json.dumps(b, ensure_ascii=False)]
json.dump(d, open(path,'w'), ensure_ascii=False, indent=2)"
```

---

## 6. 故障排查

| 现象 | 排查 |
| --- | --- |
| EmonLens 无 Trace | ① `~/.emonlens/config.json` 里的 `host`/`ingest_key` 是否正确（setup 写的，改这里即可，不用改脚本）；② EmonLens 是否可达：`curl <host>:8300/actuator/health`；③ Key 是否 ACTIVE；④ `~/.codex/hooks.json` 里的命令指向的解释器是否还有 `emonlens` 包（`pip uninstall` 后钩子会失效） |
| Codex 提示钩子未信任 | 首次触发后按 Codex 提示确认；或检查 `~/.codex/hooks.json` 中 `hooks.state` 是否出现你的 entry |
| 想看上报了什么 | 桥接**默认**就把每次事件原文写进 `<state_dir>/debug.log`（`~/.emonlens/codex/debug.log`，超 2MB 自动清空；`EMONLENS_STATE_DIR` 可改位置）。要看上报本身的成功/失败，另设 `EMONLENS_DEBUG=1` |
| 事件字段取不到（input/output 空） | Codex 版本字段名可能变化：桥接 `_pick()` 已做候选键兜底；把事件原文 dump 出来核对后在 `_pick` 调用处补充键名 |
| 部分工具没有上报 | 仅 `PostToolUse` 事件产生 tool span；被权限拦截未执行的工具（PermissionRequest 拒绝）不会触发；只发了 `PostToolUse` 而没有 `PreToolUse` 时工具耗时记 0ms |
| 有工具调用、但没有模型/tokens 的空 trace | 早期单文件副本的已知问题：Codex 在 resume/上下文压缩时会重复触发 SessionStart 并覆盖轮次状态，把同轮的 LLM/根 span 分流到别的 trace。改用 SDK 桥接（`emonlens-codex-setup`）后按 turn_id 分组，不再出现；历史数据按「会话」维度查看仍完整 |
| 上报失败影响 Codex 吗 | 不会——桥接全量 try/except 静默；hooks timeout 10s，上报超时 5s，均在预算内 |

---

## 7. 桥接实现

桥接逻辑随 SDK 分发：`emonlens/codex_hooks.py`（pip 安装后可直接
`python3 -m emonlens.codex_hooks <action>` 调用）。核心流程：

- 配置：env `EMONLENS_INGEST_KEY/HOST` 优先，回退 `~/.emonlens/config.json`
- 状态：`~/.emonlens/codex/<session_id>.json` 跨进程记录主轮次/提问/开始时间/待配对的工具调用；
  traceId 由主轮次派生（`codex-<session前12位>-<主轮次turn_id哈希8位>`），同一轮的全部
  tool span（含子 agent 的 turn）与 Stop 的 LLM/根 span 因此恒落同一条 trace——Codex 在
  resume/压缩时会重复触发 SessionStart、主/子 agent 并发写状态，均不影响分组
- 上报：urllib 直连 `/api/v1/ingest/events`，任何失败静默（不影响 Codex）

服务端幂等：同一 `(traceId, spanId)` 重复上报会被跳过，同批其余数据正常落库
——重放/重试不会产生重复数据，也不会丢数据。

---

## 8. 数据契约速查

| 事件 | 上报内容 | 备注 |
| --- | --- | --- |
| `SessionStart` | 无上报，初始化状态文件（已有进行中轮次时不覆盖） | resume / compact 会重复触发 |
| `UserPromptSubmit` | 无上报，登记主轮次与提问 | 本轮 traceId 由该 turn_id 派生；子 agent 工具随后自动挂到本轮 |
| `PostToolUse` | 1 条 tool 子 span（实时） | `toolName` / `tool_input` / `tool_response`；与同轮 pre/post 配对算真实耗时 |
| `Stop` | 2 条 span：LLM 轮次 span（`assistant_turn`，含模型/回答/tokens）+ 根 span（`codex_session`） | input=提问，output=最终回答；随后清理本轮状态 |

*手册版本 V1.1（pip 分发 + setup 一键注册）· 已在 Codex CLI 0.142.5 + EmonLens 0.1.0 端到端验证*
