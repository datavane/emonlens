# emonlens (Python SDK)

EmonLens Agent Observability SDK — 零第三方依赖，3 个环境变量接入。

## 安装

```bash
pip install emonlens                # PyPI（发布后）
pip install -e ./emonlens-sdk-python  # monorepo 本地开发
```

## 快速开始

```bash
export EMONLENS_HOST="http://emonlens:8300"
export EMONLENS_INGEST_KEY="tk-xxxx-xxxx-xxxx"
export EMONLENS_AGENT="my-agent"
# 可选：EMONLENS_ENVIRONMENT=production / EMONLENS_AGENT_VERSION=v1
```

```python
from emonlens import Trace

with Trace(session_id="chat-1", input="用户问题") as tr:
    tr.llm("call_model", model="gpt-4o-mini", input=prompt, output=answer,
           input_tokens=100, output_tokens=50)     # 成本自动估算
    tr.tool("query_db", input=sql, output=rows)
# 退出时自动补 endTime/status 并后台批量上报（失败不影响业务）
```

未配置环境变量时 SDK 自动进入 **no-op 模式**（所有调用静默跳过，无任何副作用）。

## 环境变量

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `EMONLENS_HOST` | ✅ | 服务地址 |
| `EMONLENS_INGEST_KEY` | ✅ | Ingest Key（Agent 接入页签发） |
| `EMONLENS_AGENT` | ✅ | agentKey |
| `EMONLENS_AGENT_VERSION` | — | 默认 v1 |
| `EMONLENS_ENVIRONMENT` | — | 默认 production |
| `EMONLENS_FLUSH_INTERVAL` | — | 批量间隔秒，默认 2 |
| `EMONLENS_MAX_BATCH` | — | 单批 span 数，默认 100 |
| `EMONLENS_DEBUG` | — | true 打印上报日志 |
| `EMONLENS_API_TOKEN` | 评测时必填 | 开放 API Token（`X-Api-Token`），需 `eval:run` / `eval:read` / `score:read`；与 Ingest Key 不是同一个凭据 |

也可代码初始化（覆盖环境变量）：

```python
import emonlens
emonlens.init(host="http://emonlens:8300", ingest_key="tk-...", agent="my-agent")
```

## 全自动埋点：@observe（推荐）

```python
from emonlens import observe, update_current

@observe()                        # 根 span（默认 agent 类型）
def answer_question(question: str) -> str:
    docs = retrieve(question)     # 子 span（retrieval）
    ans = generate(question)      # 子 span（llm）
    return ans

@observe(span_type="retrieval")
def retrieve(q): ...

@observe(span_type="llm")
def generate(q):
    ...
    update_current(model="gpt-4o-mini", input_tokens=100, output_tokens=50)
    return ans
```

- 入参/返回值自动序列化为 input/output；异常自动 ERROR 并重抛
- 嵌套调用自动挂树（sync/async 均可）；跨线程用 `emonlens.propagate(fn)`
- `@observe(session_id="chat-1")` 可直接绑定会话

## OpenAI drop-in（一行替换）

```python
from emonlens.integrations.openai import OpenAI   # 替换 from openai import OpenAI
client = OpenAI()
resp = client.chat.completions.create(...)        # 自动记录 model/tokens/耗时
# 已有实例：from emonlens.integrations.openai import wrap_openai; wrap_openai(client)
```

## 评测 SDK / CLI（CI 准入）

观测数据面用 `EMONLENS_INGEST_KEY`，评测控制面用 `EMONLENS_API_TOKEN`，两者不要混用：

```bash
export EMONLENS_HOST="http://localhost:8300"
export EMONLENS_API_TOKEN="eak-..."
```

Python：

```python
import emonlens

result = emonlens.evaluate(
    dataset_id="ds-...",
    suite_id="suite-...",          # 或 evaluator_ids=["eval-1"]
    mode="TRACE",                  # TRACE / REPLAY
    wait=True,
)
run = result["run"]
gate = result.get("gate") or {}
print(run["id"], run["status"], gate.get("decision"))

# 默认 FAIL/BLOCKED/UNKNOWN 都失败；可自定义 fail_on
emonlens.assert_test(result)
emonlens.assert_test(result, fail_on=["FAIL", "BLOCKED"])

# 历史 Gate 必须消费 usable + decision，不能只看旧 decision=PASS
verification = emonlens.gate_check("gate-...")
assert verification["usable"] and verification["decision"] == "PASS"
```

CLI：

```bash
# 提交并等待，非零退出码可直接用于 CI
emonlens eval run --dataset ds-... --suite suite-... --mode TRACE \
  --wait --timeout 900 --assert

# 提交后不等待，输出 JSON
emonlens eval run --dataset ds-... --evaluator eval-1 --json

# 校验历史 Gate
emonlens gate check --gate-id gate-... --json
```

退出码：`0` 通过；`2` run 失败/ERROR 或 Gate FAIL；`3` UNKNOWN/BLOCKED/断言失败/Gate 不可用；`4` 配置或 HTTP 错误；`5` 等待超时。

完整的 v2 样本、导入导出、合成、CLAIMS/TRAJECTORY、缓存和成本说明见仓库
`docs/EmonLens评测增强功能使用指南.md`。

## CLI Agent 一键接入（Codex / Claude Code / ZCode）

```bash
emonlens-codex-setup  --host http://emonlens:8300 --key tk-xxxx
emonlens-claude-setup --host http://emonlens:8300 --key tk-xxxx
emonlens-zcode-setup  --host http://emonlens:8300 --key tk-xxxx
```

自动写 `~/.emonlens/config.json` 并向对应 Agent 的 hooks 配置注册事件
（SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / Stop；ZCode 额外注册
PostToolUseFailure；保留已有钩子、幂等）。之后正常使用对应 Agent，调用数据自动上报：

- 根 span：input=提问、output=最终回答（Claude 从 transcript 提取，ZCode 事件直接携带）
- 每次 tool 调用一个 tool 子 span（ZCode 工具失败上报 ERROR span，并按 Pre/Post 配对真实耗时）
- 每轮 LLM span（Codex 从 rollout 读 `last_token_usage`；ZCode 按 `turnId` 聚合
  `~/.zcode/cli/rollout` 中本轮全部模型请求，含缓存命中与推理 tokens）

详细步骤与故障排查见仓库 `docs/Codex接入操作手册.md` /
`docs/Claude接入操作手册.md` / `docs/ZCode接入操作手册.md`。

## Prompt 运行时拉取与 Trace 关联（对标 Langfuse get_prompt）

从平台提示词库按标签拉取版本（60s TTL 缓存，拉取失败返回 None 不阻塞业务），并自动在 Trace 上建立 prompt 关联——版本用量与通过率统计零手工成本：

```python
import emonlens

p = emonlens.get_prompt("support-assistant")                # production 标签；TTL 缓存
text = p.compile(question="工单 #1024 的处理进度是多少？")      # 渲染 {{变量}}（未提供的保留原样）

with emonlens.Trace(prompt=p, session_id="chat-42") as tr:  # trace 自动关联 prompt v?
    tr.llm("call_model", input=text, output=answer)

p2 = emonlens.get_prompt("support-assistant", label="latest", force_refresh=True)  # 绕过缓存
```

数据面接口 `GET /api/v1/ingest/prompts/{name}/resolve`（X-Trust-Key 鉴权）同时可被其他语言/网关直接复用。

## LangChain 集成

```python
from emonlens.integrations.langchain import EmonLensCallbackHandler
handler = EmonLensCallbackHandler(session_id="chat-1")
chain.invoke(input, config={"callbacks": [handler]})
```

## API

| API | 说明 |
| --- | --- |
| `@observe(name, span_type, session_id, ...)` | 全自动埋点装饰器 |
| `update_current(model, input_tokens, output_tokens, cost_usd)` | 补充当前 span 元数据 |
| `set_session(session_id, user_id, environment)` | 为当前 Trace 设置归属 |
| `trace_context(name=..., session_id=...)` | 把任意代码块归为一个 Trace |
| `propagate(fn)` | 把 Trace 上下文复制到新线程 |
| `Trace(thread_id, user_id, environment, input, prompt)` | 手动埋点（上下文管理器；thread_id 为多轮会话分组，LangSmith 命名对齐） |
| `tr.llm / tr.tool / tr.retrieval / tr.event` | 手动子 span |
| `emonlens.flush(timeout=5)` | 同步排空队列（脚本退出前调用） |
| `emonlens.init(...)` | 代码方式初始化 |
| `emonlens.get_prompt(name, label, cache_ttl, force_refresh)` | 拉取提示词（TTL 缓存，失败返回 None） |
| `Prompt.compile(**vars)` | 渲染提示词变量 |
| `tr.llm(..., cached_input_tokens=n)` | 提示缓存命中量（输入 tokens 中命中缓存的部分，独立统计） |
| `Trace(prompt=p, ...)` | Trace 关联 prompt 版本（自动 attributes） |

## 可靠性

- 后台线程批量上报（2s/100 条），业务零阻塞
- 网络/5xx 指数退避重试 2 次；持续失败静默丢弃
- 平台限流（429 + Retry-After）时按服务端指示退避后重试本批，不丢数据也不落盘失败件
- Ingest Key 失效（401）时告警一次并停止上报
- 队列打满丢弃新数据保业务；`client.dropped` 可查丢弃数
- 自定义脱敏：`emonlens.init(..., redact=lambda span: ...)`
