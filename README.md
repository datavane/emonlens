<p align="center">
  <img src="docs/assets/emonlens-logo.png" height="120" alt="EmonLens" />
</p>

<h3 align="center">EmonLens</h3>

<p align="center">开源的 AI Agent 可观测与评测平台，可自托管。</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-11-orange" alt="Java 11" />
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7-6DB33F" alt="Spring Boot 2.7" />
  <img src="https://img.shields.io/badge/Vue-3.5-42B883" alt="Vue 3.5" />
  <img src="https://img.shields.io/badge/Python-3.9%2B-3776AB" alt="Python 3.9+" />
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue" alt="Apache-2.0" />
</p>

<p align="center">
  <a href="#快速开始">快速开始</a> ·
  <a href="#文档">文档</a> ·
  <a href="#参与贡献">参与贡献</a>
</p>

---

EmonLens 接收 Agent 运行时的 trace 与 span，在这份数据上做成本核算、行为检测、离线评估和上线准入，最后收敛为 Finding → Incident 告警与五维信任分。

服务端是一个 Spring Boot 进程，包含 REST API、ingest、检测/评估/评分引擎和后台任务，元数据存放在 MySQL 8，span 数据面可换成 StarRocks；控制台是 Vue 3 单页应用；接入侧提供 HTTP/JSON、OTLP 与零依赖 Python SDK。

> 当前版本 0.1.0，接口与数据模型仍在演进。

## 功能

### 观测与成本

- Trace 列表、Span 树、瀑布图、双 trace 对比、会话聚合、用户洞察、标签与评论、CSV 导出。
- 10 种 span 类型：`agent` / `workflow` / `llm` / `tool` / `retrieval` / `guardrail` / `handoff` / `memory` / `approval` / `business_action`。
- Token 区分输入、输出与缓存命中三类，缓存命中默认按输入价的 10% 计费；模型价格后配会回溯重算历史 span。
- 用量按 model / agent / user 三个维度下钻。

### 检测与告警

- 四类监控规则：VALIDATION、TRAJECTORY、METRIC、EVALUATION。VALIDATION 的 `where` 表达式运行在只读 SpEL 沙箱里，只放行 10 个字符串与集合方法。
- 内置循环检测：完全重复、循环块、步数超限。
- Finding 按指纹归并为 Incident，状态机为 `OPEN → ACKNOWLEDGED → RESOLVED`。
- 通知走邮件与 Webhook，Webhook 带 HMAC-SHA256 签名；事件表原子认领、退避重试，死信可人工重投。

### 评估

- 四类评估器：表达式、正则、LLM Judge、工具轨迹（TRAJECTORY，只比对工具序列，不调用模型）。
- Judge 模板版本化，按 claim 逐条判分；结果缓存以 sample、模板、模型为 key。
- 数据集支持多轮样本、检索上下文与期望工具；CSV / JSONL 导入导出提供 dry-run、原子写入和 contentHash 幂等。
- 按比例或按错误强制采样、人工标注队列、LLM 合成候选（critic 过滤 + 人工批准）、trace 回放与批量实验，两个 run 之间可做逐样本 diff。

### 准入

- Gate 输出 `PASS` / `FAIL` / `BLOCKED`，并给出 `verification.usable`；CI 只应消费 PASS，BLOCKED 表示证据不足以判断。
- 数据集 revision 与样本的用途、风险、切分绑定，评估器与 Judge 模板带版本和配置指纹。
- 另有 Judge 人工盲审校准、Pairwise 成对偏好、Domain Pack、外部业务实验证据，以及质量案例回访闭环。

### 信任分

五个维度按权重取几何平均，任一维度塌陷都会显著拉低总分：

```text
overall = performance^0.25 × behavior^0.25 × output^0.30 × security^0.10 × context^0.10
罚分：CRITICAL +40 / HIGH +15 / WARNING +5，score = 100 / (1 + penalty / 80)
状态：存在 CRITICAL Finding → CRITICAL；overall ≥ 85 → TRUSTED；否则 AT_RISK
```

每次评分留存快照，便于回溯结论是怎么算出来的。

### 平台

- 工作空间隔离、27 个权限点、预置角色、操作审计。
- 开放 API（`X-Api-Token` + scope），以及对运行中实例的黑盒验收脚本。
- 内容采集四档：`NONE` / `HASHED` / `REDACTED` / `FULL`，默认 `REDACTED`。
- Prompt 版本管理（`production` / `latest` 标签、行级 diff）与 Playground（A/B 双跑、数据集批量试跑）。

## 快速开始

```bash
git clone https://github.com/datavane/emonlens.git && cd emonlens
cp .env.example .env
# 修改 .env：JWT 密钥、Ingest Key、admin 密码、数据库口令都换成强随机值
docker compose up -d --build
```

打开 <http://localhost:8300>，用 `admin` 和 `.env` 里的 `EMONLENS_ADMIN_PASSWORD` 登录。首次启动会自动建库并写入系统种子（工作空间、RBAC、菜单、admin、通知模板），不含演示业务数据，页面上的数据全部来自 Agent 上报。

不想装 MySQL 时可用内存 H2 演示模式：

```bash
SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run    # JDK 11+
```

登录 `admin / 123456`，数据不持久化，启动日志会提示演示凭据。生产部署使用 `prod` profile（Compose 与 tar.gz 默认），启动门禁会拒绝弱的 JWT 密钥、dev ingest key 和默认 admin 口令。

## 接入 Agent

| 方式 | 入口 | 适用场景 |
| --- | --- | --- |
| Python SDK | `pip install emonlens`（PyPI 发布前 `pip install -e ./emonlens-sdk-python`） | 自研 Agent，`@observe` / `Trace` 埋点 |
| OpenAI drop-in | `from emonlens.integrations.openai import OpenAI` | 已用 OpenAI SDK，换一行 import |
| LangChain | `EmonLensCallbackHandler` | 现有 Chain / Agent 挂 callback |
| CLI Agent hooks | `emonlens-codex-setup` / `emonlens-claude-setup` / `emonlens-zcode-setup` | Codex CLI、Claude Code、ZCode，按轮次分组上报 |
| 评测 SDK / CLI | `emonlens.evaluate()` / `emonlens eval run` / `emonlens gate check` | CI 提交数据集评测、消费 Gate、非零退出码 |
| OTLP/HTTP | `POST /api/v1/ingest/otlp/v1/traces` | 任意 OpenTelemetry Collector 或其他语言 |
| HTTP/JSON | `POST /api/v1/ingest/events` | 自定义上报，单批 ≤ 500 span |

```bash
export EMONLENS_HOST="http://localhost:8300"
export EMONLENS_INGEST_KEY="<Agent 接入页签发的 Ingest Key>"
export EMONLENS_AGENT="my-agent"
```

```python
from emonlens import observe

@observe(span_type="retrieval")
def retrieve(question: str) -> list[str]:
    ...  # 你的检索实现

@observe(span_type="llm")
def generate(question: str, docs: list[str]) -> str:
    ...  # 你的 LLM 调用

@observe(name="answer")            # 根 span，默认 agent 类型
def answer(question: str) -> str:
    return generate(question, retrieve(question))
```

SDK 在后台批量上报（2s 或 100 条），业务调用不被阻塞；网络失败按指数退避，Ingest Key 失效时告警一次并停止上报。未配置环境变量时进入 no-op 模式，不产生任何副作用。

需要更细的埋点时手写 trace：

```python
from emonlens import Trace

with Trace(session_id="chat-1", input="用户问题") as tr:
    tr.llm("call_model", model="gpt-4o-mini", input=prompt, output=answer,
           input_tokens=100, output_tokens=50, cached_input_tokens=60)
    tr.tool("query_db", input=sql, output=rows)
```

## 架构

```text
Agent / SDK / OTel Collector
   │  Ingest Key
   ▼
emonlens-server（单进程）
   ├─ 接入   校验 → 令牌桶限流 → 有界队列（默认 50000）
   ├─ 落库   批处理线程 → span 幂等写入 → trace 汇总 → 成本估算
   ├─ 事件   TRACE_COMPLETED / FINDING / INCIDENT / SCORE …（DB 原子认领）
   │          ├─ 检测：4 类监控规则 + 循环检测
   │          ├─ 评估：表达式 / 正则 / LLM Judge / 工具轨迹
   │          ├─ 评分：五维加权几何平均
   │          └─ 通知：邮件 / Webhook
   └─ 控制台  内嵌 Vue 3 前端 + REST API
                  │
                  ▼
           MySQL 8（元数据与治理面；span 数据面可选 StarRocks）
```

## 与 Langfuse、LangSmith 的区别

| | EmonLens | Langfuse | LangSmith |
| --- | --- | --- | --- |
| 定位 | 观测 + 行为检测 + 准入与信任结论 | LLM 工程平台 | LLM 调试与评估 |
| 开源协议 | Apache-2.0 | MIT（部分企业功能除外） | 闭源 SaaS |
| 自托管 | 单 jar / Compose | 支持 | 企业版 |
| 服务端技术栈 | Java 11 + Spring Boot + MySQL | TypeScript + ClickHouse | 托管服务 |
| Trace / 会话 / 成本 | 支持 | 支持 | 支持 |
| Prompt / Playground | 支持 | 支持 | 支持 |
| 评估 / 数据集实验 | 支持 | 支持 | 支持 |
| 内置循环与轨迹检测 | 支持 | 需自定义 | 需自定义 |
| 五维信任分 | 支持 | 无 | 无 |
| Incident 状态机 | 支持 | 无 | 无 |

Trace、Prompt、评估、数据集是三家共有的基础能力，EmonLens 在这些能力上参考了同类产品的做法。差异在于把行为检测、事件闭环和信任评分放进数据模型，而不是留给用户自己拼装。

## 部署形态

| 形态 | 命令 / 产物 | 适用 |
| --- | --- | --- |
| Docker Compose | `docker compose up -d --build` | 单机交付，自带 MySQL |
| tar.gz 分发件 | `make dist` → `emonlens-dist/target/emonlens-0.1.0-bin.tar.gz` | 不能跑 Docker 的服务器，自备 MySQL 8.0 |
| 单 jar | `make build` → `emonlens-server/target/emonlens-server-0.1.0.jar` | 自有调度或容器平台 |
| 离线镜像 | `make build && make image-offline` | 构建机访问不到 Maven / npm 仓库 |

上线前请过一遍[部署运维手册](docs/EmonLens部署运维手册.md)里的安全基线，几个常见坑：

- 所有凭据使用强随机值，`prod` profile 的启动门禁会拒绝默认值。
- Compose 默认把端口绑在 `127.0.0.1`；对外暴露请前置 nginx + TLS，并在反代后设置 `EMONLENS_TRUST_XFF=true`。
- 登录防爆破与接口限流是实例内存态，多实例部署时由网关承担全局限流。
- span 先入进程内队列（默认 50000），需要保证优雅停机（SIGTERM），`kill -9` 会丢队列里的数据。
- 后台任务靠数据库原子认领，可以横向加副本；api 实例也建议保持 `EMONLENS_WORKER_ENABLED=true`。

## 本地开发

前置：JDK 11+、Maven 3.6+、Node 20+、Python 3.9+。

```bash
make test        # 后端全量测试（内存 H2，不依赖本地 MySQL）
make sdk-test    # Python SDK 测试
cd emonlens-ui && npm ci && npm run dev   # dev server 5175，/api 代理到 8300
make build       # UI 内嵌 + fat jar
make dist        # 分发件：jar + 启停脚本 + SQL + 配置样例 + SDK + 文档
make smoke PASS=<admin 密码>    # 对运行中的实例做冒烟检查
make verify PASS=<admin 密码>   # 全功能黑盒验收，幂等可重复执行
```

## 仓库结构

```text
emonlens/
├── emonlens-server/      REST API + ingest + 检测/评估/评分引擎 + 后台任务 + 内嵌控制台
├── emonlens-ui/          Vue 3 + Vite 6 + naive-ui，构建产物写入 server static/
├── emonlens-sdk-python/  零依赖 Python SDK：@observe / Trace / OpenAI / LangChain / CLI hooks
├── emonlens-common/      ApiResult / ErrorCode / SHA-256 等共享契约
├── emonlens-dist/        release profile 下打包 tar.gz
├── docs/                 操作手册
├── scripts/              smoke.sh、verify_all.py、compose.sh
├── Dockerfile            多阶段构建（UI → jar → 运行时镜像）
└── Makefile              build / test / dist / image / release / smoke / verify
```

## 文档

| 我想… | 看这里 |
| --- | --- |
| 部署到服务器 | [部署运维手册](docs/EmonLens部署运维手册.md) |
| 日常使用平台 | [操作指南](docs/EmonLens操作指南.md) |
| 做评测与准入闭环 | [评测与准入操作指南](docs/EmonLens评测与准入操作指南.md) |
| 用 v2 样本、导入导出、合成、CLAIMS、TRAJECTORY、CLI | [评测增强功能使用指南](docs/EmonLens评测增强功能使用指南.md) |
| 把 Agent 数据接进来 | [Agent 接入教程](docs/Agent接入教程.md) |
| 接入 Codex CLI / Claude Code / ZCode | [Codex](docs/Codex接入操作手册.md) · [Claude](docs/Claude接入操作手册.md) · [ZCode](docs/ZCode接入操作手册.md) |
| Python SDK 详细用法 | [SDK README](emonlens-sdk-python/README.md) |

## 路线图

- **Context 维度接入外部证据**：目前 `context` 只看 Finding，后续接收外部数据质量与上下文质量结果。
- **IM 通知渠道**：枚举已预留，当前实现邮件与 Webhook。
- **Helm Chart**：目前只有单机 Compose 与 tar.gz。

## 参与贡献

欢迎 Issue、讨论与 PR，开始之前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

- 报告 Bug 时附上版本、复现步骤和相关日志，并先脱敏（不要贴 Ingest Key、JWT、数据库口令）。
- 功能建议请说明使用场景与期望行为。
- 一个 PR 只做一件事；改行为请补测试；影响使用方式时同步更新 `docs/` 下受影响的文档。

## 安全

- 口令使用 PBKDF2WithHmacSHA256（20000 轮 + 16 字节随机盐）；JWT 为自实现 HS256，登出即写入吊销黑名单。
- 27 个权限点 + 工作空间行级隔离；跨工作空间访问统一返回“资源不存在”，不暴露资源是否存在。
- 采集层默认 `REDACTED`，入库前按正则脱敏密钥、身份证、手机号、邮箱。
- Validation 的 `where` 跑在只读 SpEL 沙箱：无类型引用、无构造器、无静态方法。
- 容器以非 root 用户运行，Actuator 只暴露 `/actuator/health`。

已知缺口：通知渠道配置与 LLM apiKey 目前明文入库（设计意图是 AES 加密），用户口令暂无复杂度策略。

## License

[Apache License 2.0](LICENSE) © EmonLens contributors
