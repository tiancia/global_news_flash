# 运维手册

[返回首页](../README.md) · [配置与处理规则](configuration.md)

## HTTP 接口

默认 `127.0.0.1:8090`。`/actuator/health` 公开，其余接口需要 `X-Ops-Token`，未配置 `NEWS_OPS_TOKEN` 时拒绝运维访问。以下路径统一以 `/ops` 开头。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/status`、`/automation` | 模式、开关、readiness 和周期记录 |
| POST | `/pause` | `{"paused":true}` 或 false，全局暂停且重启保留 |
| POST | `/run` | 执行一轮真实流程，不自动解除暂停 |
| GET | `/sources`、`/ingestion` | 来源准入、游标、错误和快照进度 |
| POST | `/sources/{id}/state` | `{"enabled":false}` 或 true；启用仍须批准 |
| POST | `/ingestion/poll` | 仅采集，不调用 AI 或发布 |
| GET | `/ingestion/inbox` | 最近待办与原因，无正文 |
| POST | `/ingestion/inbox/{id}/retry` | 对准入有效的失败或挂起项重新排队 |
| GET | `/events`、`/editor-jobs` | 编辑版本和模型任务 |
| POST | `/editor-jobs/{id}/resolve-cost` | `{"actualMicros":0,"note":"核对依据"}`，结算不确定费用 |
| POST | `/editor-jobs/{id}/retry` | 对明确拒绝或完成费用核对的任务申请新编辑 |
| GET | `/publications` | 待发布及已发布记录与正文预览 |
| GET | `/publications/{id}/preview` | 实际发送文本，包含修订前缀 |
| GET | `/publications/{id}/attempts`、`/publications/{id}/resolutions` | 尝试记录与核对审计 |
| POST | `/publications/{id}/dispatch` | 处理指定任务，服从所有检查与限额 |
| POST | `/publications/{id}/resolve` | `resolution`、`note`；确认送达还需 `messageId` |
| POST | `/recover` | 恢复发布租约和模型任务，不调用外部接口 |
| GET / POST | `/digests` / `/digests/run` | 日报记录 / 检查是否到期生成 |
| GET | `/alerts`、`/automation/alerts`、`/notifications` | 告警和 webhook 投递状态 |
| POST | `/notifications/flush` | 处理已启用的 webhook 告警 |
| GET | `/report` | 编辑、发布和成本统计 |
| POST | `/demo` | 仅 OFFLINE；`scenario` 可为 VALID、REVISED、STALE、DISCOVERY、TITLE_ONLY |

## Docker 与本地数据库

根目录 [compose.yml](../compose.yml) 默认只启动 MySQL 8.4。库和用户均为 `global_news_flash`，端口 `127.0.0.1:3307`，应用账号和 root 的本地开发默认密码为 `123456`。数据目录为 `A:/DockerData/global-news-flash/mysql`，业务表由应用启动时执行 Flyway 迁移创建。

```powershell
docker compose up -d
docker compose ps
docker compose logs --tail 80 db
docker compose stop
```

IDE Active profiles 设为 `mysql`，本机连接参数在 [application-mysql.yml](../src/main/resources/application-mysql.yml)。`NEWS_DB_URL`、`NEWS_DB_USER`、`NEWS_DB_PASSWORD` 可覆盖应用连接，`NEWS_DB_ROOT_PASSWORD` 可覆盖容器初始化密码。已有数据库密码须在 MySQL 中修改，单改环境变量不能更新已有账号。

可选的应用容器使用 [compose.app.yml](../compose.app.yml)，先配置独立运维令牌，再执行：

```powershell
docker compose -f compose.yml -f compose.app.yml up -d --build
```

容器连接 `db:3306`，本机后端连接 `127.0.0.1:3307`，两者访问同一数据库。应用容器默认使用 `mysql` Profile，真实采集、编辑和发布需按配置说明显式开启。容器非 root，运维端口仅映射回环地址。Dockerfile 构建跳过测试，发布前须另行执行 `mvnw verify`。

## 备份与恢复

备份脚本生成 SQL 和 SHA256 校验文件，默认使用 `.tools/backups`，也可指定外部备份目录：

```powershell
.\scripts\backup-mysql.ps1 -BackupDirectory <备份目录>
```

恢复会替换业务表。先停止本机 IDE 后端和 app 容器，再显式执行：

```powershell
.\scripts\restore-mysql.ps1 -BackupFile <绝对SQL路径>
```

脚本校验备份、检查容器化 app 是否停止，恢复后强制暂停并记录审计，不自动启动应用。本机 IDE 后端需自行停止；备份期间不要执行数据库结构迁移。恢复后核对备份时间之后实际送达的 Telegram 消息，再恢复发布。

## 异常与恢复

| 状态 | 处理原则 |
| --- | --- |
| 模型明确拒绝 | 401 / 429 等确定拒绝可按退避策略重试 |
| 模型 `UNCERTAIN` | 超时、中断或未知 usage 保留预算并停止自动重试；核对费用后 resolve-cost，再显式 retry |
| Telegram 429 | 遵循 retry_after |
| Telegram `UNKNOWN` | 超时、5xx 或无法确认结果不盲目重发，先核对频道和尝试记录 |

Telegram 核对使用 `CONFIRMED_SENT` 或 `CONFIRMED_NOT_SENT`，必须提供 note；确认送达还需 messageId。确认未发送转 FAILED，不自动重发。模型费用核对只结算费用，不发布已丢失响应；两类不确定状态分别处理。

本地 H2 测试、离线 smoke 和配置检查不等于真实供应商、MySQL 并发、Telegram 发布或灾难恢复演练通过。
