# 贡献指南

感谢你愿意花时间改进 EmonLens。本文说明如何报告问题、提交代码，以及这个仓库的几条硬性约定。

## 行为准则

- 对事不对人：讨论技术方案，不评价人。
- 不泄露凭据与真实数据：Issue、PR、日志截图里不要出现 Ingest Key、JWT、数据库口令、用户数据。
- 安全问题请勿公开披露细节，先私下联系维护者。

## 报告问题

一个可处理的问题描述通常包含：

1. **版本**：分发件 `VERSION` 文件，或 jar 内 `META-INF/MANIFEST.MF` 的 Implementation-Version。
2. **部署形态**：Docker Compose / tar.gz / 单 jar，以及数据库类型。
3. **复现步骤**：最小、可重复。
4. **期望行为 vs 实际行为**。
5. **证据**：相关日志（脱敏后）、接口返回、页面截图。

## 提交代码

```bash
# 1. 从最新主干切分支
git checkout -b fix/trace-idle-sweep

# 2. 改动 + 本地验证
make test          # 后端 208 个测试（内存 H2，不依赖本地 MySQL）
make sdk-test      # SDK 55 个测试
cd emonlens-ui && npm run build   # 前端必须能构建

# 3. 提交（一个 PR 只做一件事）
git commit -m "fix: 空闲 trace 收口在并发下重复完成"
```

### 硬性约定

| 项 | 约定 |
| --- | --- |
| 代码风格 | 跟随所在文件；不引入新的格式化工具或无关重排 |
| 注释 | 只写代码本身表达不了的约束；不写"这行做了什么" |
| 测试 | 修 Bug 补回归测试；加能力补契约测试 |
| 数据表 | 先改 `schema-h2.sql`，再同步 `sql/emonlens-mysql.sql` 与实体/Mapper |
| 错误返回 | 沿用 `ApiResult` + 业务码，HTTP 200 + `code != 0`（限流等少数场景除外） |
| 权限 | 新接口必须挂 `@RequiresPermission`，前端路由同步 `meta.perm` |
| 文档 | 改行为 → 更新 `docs/` 下受影响的文档；设计文档由维护者内部同步（不随本仓库发布） |

### PR 描述

- 说明**为什么**要改，而不只是改了什么。
- 列出验证方式（命令、关键输出、页面路径）。
- 涉及界面改动请附截图；涉及接口改动请附请求/响应示例。

## 仓库结构约定

- `emonlens-server` 是唯一部署单元，不要再拆微服务；新能力按 `engine/`、`api/`、`storage/` 归位。
- `emonlens-ui` 构建产物直接写入 `emonlens-server/src/main/resources/static/`，不要提交构建产物。
- `emonlens-sdk-python` 保持**零第三方依赖**；新增依赖需要充分理由。
- `emonlens-dist` 只在 `release` profile 参与构建，日常 `make test` / `make build` 不应触发 assembly。

## 文档分工

- **设计文档**（维护者内部维护，不随本仓库发布）：架构、数据模型、算法、ADR。不写操作步骤。
- **操作手册**（`docs/`）：部署、接入、日常操作。必须"照做即可"，不解释架构决策。
- 同一条事实只在一处维护；两边需要互相引用时用链接。

## License

提交即表示你同意以 [Apache License 2.0](LICENSE) 授权你的贡献。
