# EmonLens 分发件

Agent Trust 可信观测平台（单 jar，内嵌控制台 UI）。

## 目录

```text
bin/emonlens.sh        启动 / 停止 / 状态 / 日志
bin/setenv.sh          环境初始化（被启动脚本 source）
conf/                  环境变量与 Spring 配置样例
lib/                   emonlens-server-<version>.jar（Spring Boot fat jar）
sql/                   MySQL 建库种子 + StarRocks 可选 DDL
sdk/                   Python SDK（wheel / sdist）
docs/                  操作手册
logs/                  运行日志（首次启动时创建）
run/                   pid 文件（首次启动时创建）
VERSION                版本与构建时间
```

## 快速开始

前置：JDK/JRE 11+、MySQL 8.0（字符集 utf8mb4）。

```bash
# 1. 建库并导入 schema（一次性；含默认工作空间/角色/admin 用户）
mysql -h127.0.0.1 -uroot -p -e "CREATE DATABASE IF NOT EXISTS emonlens DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h127.0.0.1 -uroot -p emonlens < sql/emonlens-mysql.sql

# 2. 写配置（所有 change-me 换成强随机值）
cp conf/emonlens.env.example conf/emonlens.env
vi conf/emonlens.env

# 3. 启动（自动等待 /actuator/health 通过）
bin/emonlens.sh start

# 4. 打开控制台，用 admin 登录
#    http://127.0.0.1:8300
#    admin / <conf/emonlens.env 中的 EMONLENS_ADMIN_PASSWORD>
```

## 常用命令

```bash
bin/emonlens.sh status        # 运行状态 + 健康检查
bin/emonlens.sh logs 200      # 跟随日志（默认 200 行）
bin/emonlens.sh restart
bin/emonlens.sh stop          # SIGTERM 优雅停机（会刷完 ingest 队列）
bin/emonlens.sh console       # 前台运行，便于排障
```

## 发布验收

```bash
# 13 项冒烟
bash scripts/smoke.sh http://127.0.0.1:8300 admin '<EMONLENS_ADMIN_PASSWORD>'

# 12 域全功能黑盒验证（84 项，幂等可重复）
python3 scripts/verify_all.py http://127.0.0.1:8300 admin '<EMONLENS_ADMIN_PASSWORD>'

# 校验历史 Gate 时可带上数据面凭据：
# EMONLENS_INGEST_KEY=<conf/emonlens.env 中的值> python3 scripts/verify_all.py ...
```

## 说明

- **profile 默认 prod**：启动门禁强制校验 `EMONLENS_JWT_SECRET`（≥16 字符）、
  `EMONLENS_INGEST_KEY`（非 `dev-trust-key-001`）、`EMONLENS_ADMIN_PASSWORD`（≥8 位）
  与 `EMONLENS_DB_PASSWORD`；库中 admin 若仍是种子口令 `123456`，会用
  `EMONLENS_ADMIN_PASSWORD` 一次性轮换。
- **Ingest Key**：`EMONLENS_INGEST_KEY` 是 SDK 上报用的数据面凭据（`X-Trust-Key`）；
  也可登录控制台在「Agent 接入」页另行签发/吊销。
- **数据库 schema 不会自动创建**：`spring.sql.init.mode=never`，必须手工执行第 1 步。
- **水平扩展**：后台任务靠 DB 原子认领天然分摊；但 span 落库是进程内内存队列，
  api 实例也请保持 `EMONLENS_WORKER_ENABLED=true`（默认），否则运行期只收不落、
  停机才刷盘，`kill -9` 会丢队列。
- **Python SDK**：`pip install sdk/emonlens-<version>-py3-none-any.whl`。

完整部署与环境变量说明见 `docs/EmonLens部署运维手册.md`。
