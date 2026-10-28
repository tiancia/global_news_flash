# 日常运维

[首页](../README.md) · [配置](configuration.md) · **运维** · [架构](architecture.md) · [模块](modules.md)

[运行控制](#运行控制) · [Docker 与数据](#docker-与数据) · [异常处理](#异常处理) · [接口索引](#接口索引)

## 运行控制

首次启动步骤见 [README](../README.md#本地启动)。默认地址为 `http://127.0.0.1:8090`，自定义端口时同步调整下面的 `$newsBase`。
`/actuator/health` 公开，所有 `/ops` 接口都需要 `X-Ops-Token`。

在已设置 `NEWS_OPS_TOKEN` 的 PowerShell 中准备请求参数：

```powershell
$newsBase = 'http://127.0.0.1:8090'
$newsHeaders = @{ 'X-Ops-Token' = $env:NEWS_OPS_TOKEN }
```

### 先查看状态

```powershell
Invoke-RestMethod "$newsBase/actuator/health"
Invoke-RestMethod "$newsBase/ops/status" -Headers $newsHeaders
Invoke-RestMethod "$newsBase/ops/automation" -Headers $newsHeaders
```

`mode`、`externalEnabled`、`paused` 在 `/ops/status` 中查看；调度开关、就绪原因和周期记录在 `/ops/automation` 中查看。

| 字段 | 含义与检查重点 |
| --- | --- |
| `mode` / `externalEnabled` | 真实流程需要 `LIVE` / `true` |
| `schedulerEnabled` / `telegramDeliveryEnabled` | 自动调度与真实发送是否开启 |
| `ready` / `reasons` | 本地配置检查是否通过；未通过时按原因处理 |
| `paused` | 是否处于全局暂停；`true` 时主自动流程不执行 |
| `runs` | 最近自动周期的结果、处理数量与原因码 |

`ready=true` 不代表远端账号额度或频道权限已经验证。发布还需通过来源、时效、预算和发送前复验。

### 暂停与恢复

暂停自动处理与发送：

```powershell
Invoke-RestMethod "$newsBase/ops/pause" -Method Post `
  -Headers $newsHeaders -ContentType 'application/json' -Body '{"paused":true}'
```

配置确认后，解除暂停：

```powershell
Invoke-RestMethod "$newsBase/ops/pause" -Method Post `
  -Headers $newsHeaders -ContentType 'application/json' -Body '{"paused":false}'
```

暂停状态写入数据库，重启保留。`live` 模式下解除暂停后，调度可真实调用模型并向目标频道发送消息。

### 按需手动触发

```powershell
# 仅采集，暂停期间也可执行；不调用 AI 或发布
Invoke-RestMethod "$newsBase/ops/ingestion/poll" -Method Post -Headers $newsHeaders

# 执行一轮完整流程，仍受就绪检查和全局暂停控制
Invoke-RestMethod "$newsBase/ops/run" -Method Post -Headers $newsHeaders
```

`/ops/run` 的 `state` 表示本轮执行状态：

| 返回状态 | 下一步 |
| --- | --- |
| `NOT_READY` | 按 `reasons` 修正配置、来源或预算问题 |
| `PAUSED` | 仍处于全局暂停；确认需要执行后解除 |
| `BUSY` | 已有周期持有租约，等待当前周期结束 |
| `COMPLETED` | 本轮已结束，继续查看素材决策与发布记录 |
| `FAILED` | 查看 `/ops/automation/alerts` 的原因码 |

`COMPLETED` 不保证每条素材都已发送。`results` 中可能有挂起或过期的结果，最终送达情况需查看发布记录和频道实际消息。

## Docker 与数据

所有 Docker 命令在项目根目录执行。

| 文件 | 用途 |
| --- | --- |
| [compose.yml](../compose.yml) | 基础服务入口，目前启动 MySQL |
| [compose.app.yml](../deploy/docker/compose.app.yml) | 可选应用容器，与根目录 Compose 配置组合使用 |
| [Dockerfile](../deploy/docker/Dockerfile) | 构建 Java 应用镜像 |

### 基础服务

MySQL 的启动命令见[本地启动](../README.md#本地启动)，日常查看和停止使用：

```powershell
docker compose ps
docker compose logs --tail 80 db
docker compose stop
```

### 可选应用容器

```powershell
docker compose -f compose.yml -f deploy/docker/compose.app.yml up -d --build
```

该容器在 Compose 中声明 `SPRING_PROFILES_ACTIVE=mysql`，默认属于 OFFLINE，不加载 `live` 的默认组合。
如需把真实流程改为全容器运行，应同时调整 app 的 Profile 和环境覆盖项；本机真实流程的完整启动步骤见 README。

应用容器非 root，运维端口仅映射回环地址；本机应用连接 `127.0.0.1:3307`，应用容器连接 `db:3306`，访问同一个数据库。
镜像构建上下文与 `.dockerignore` 均在根目录。镜像构建跳过测试，发布前的验证命令见[开发检查](architecture.md#开发检查)。

### 数据与备份

MySQL 数据位于 `A:/DockerData/global-news-flash/mysql`，连接参数见[数据库配置](configuration.md#数据库)。
当前仓库没有备份与恢复脚本，备份需使用 MySQL 工具，并记录备份时间。备份期间避免执行数据库结构迁移。

恢复时按以下顺序处理：

1. 停止 IDE 后端和应用容器，保留恢复前的数据备份。
2. 使用 MySQL 工具恢复业务库。
3. 首次启动设置 `NEWS_AUTOMATION_ENABLED=false`、`NEWS_TELEGRAM_ENABLED=false`，确认全局暂停。
4. 核对备份时间之后的实际投递记录，再恢复开关并解除暂停。

## 异常处理

| 现象或状态 | 处理方式 |
| --- | --- |
| 应用无法连接 MySQL | 查看 `docker compose ps` 和 db 日志，核对连接地址与已有账号密码 |
| 健康检查正常但没有自动处理 | 检查 Profile、两个执行开关、`ready/reasons` 和 `paused` |
| 来源被禁用或无有效待办 | 查看 `/ops/sources`、`/ops/ingestion` 和 `/ops/ingestion/inbox` |
| 同一来源持续报错且快照 offset 不动 | 按[来源快照停滞](#来源快照停滞)核对正文抓取和持久化进度 |
| 运维接口返回 401 | 检查请求头、令牌是否一致，以及启动进程是否加载了 `NEWS_OPS_TOKEN` |
| 模型明确拒绝 | 修正密钥、额度等原因；对可重试任务显式提交 retry |
| 模型 `UNCERTAIN` | 保留预算，先核对供应商账单，再结算费用并单独申请重试 |
| Telegram 429 | 按 `retry_after` 和本地限额退避 |
| Telegram `UNKNOWN` | 先核对频道、尝试记录及实际消息，不盲目重发 |

### 来源快照停滞

查看 `/ops/ingestion` 的 `snapshotPending`、`snapshotOffset`、`consecutiveFailures`、`errorCode` 和 `nextPollAt`。正常分批时快照可以暂时存在；只有跨多次实际轮询仍报错且 offset 不动，才需要按停滞排查。

当前 Fed/RSS 正文抓取中，一篇文章 HTTP 失败或正文提取失败会中断整页。整页没有提交时，成功抓取的其他素材也不会入库；持久化快照保留，退避结束后继续同一页。重启不会自动换成最新 feed。

先核对来源页面是否仍可访问、主机是否获准、正文 selector 是否匹配，以及失败原因。可通过来源状态接口暂时禁用该来源，继续处理其他已准入来源。当前没有逐篇隔离、受控跳过或重置来源快照的运维接口；持续失败需要代码恢复能力，不能把正常重试或重启当作已经恢复。不要为绕过失败而放开来源准入或盲目删除待办、编辑和发布记录。

### 费用与投递核对

**模型费用不确定**：先核对供应商 usage 或账单，再调用 `/ops/editor-jobs/{id}/resolve-cost`。

| 字段 | 要求 |
| --- | --- |
| `actualMicros` | 已核实的实际费用，非负整数，单位为微美元 |
| `note` | 核对依据，1～1000 个字符 |

结算后任务转为 `ACCOUNTED`。如需新成稿，再调用该任务的 `/retry`；明确拒绝的 `RETRYABLE` 任务也允许重试。
重试可能产生新的模型请求和费用。费用结算不会发布丢失的响应，发布仍受暂停和发送前复验约束。

**Telegram 投递不确定**：核对频道实际消息和 `/attempts` 后，调用 `/ops/publications/{id}/resolve`。

| 字段 | 要求 |
| --- | --- |
| `resolution` | `CONFIRMED_SENT` 或 `CONFIRMED_NOT_SENT` |
| `messageId` | 确认送达时必填；确认未发送时不传 |
| `note` | 核对依据，1～1000 个字符 |

确认未发送时任务转为 FAILED，不自动重发。两类核对都保留审计记录，不能用费用结算替代投递核对。

## 接口索引

以下路径均以 `/ops` 开头，`{id}` 替换为实际记录 ID。

[状态与调度](#状态与调度) · [采集与编辑](#采集与编辑) · [发布](#发布) · [日报告警与演示](#日报告警与演示)

### 状态与调度

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/status` | 模式、暂停与运行开关 |
| GET | `/automation` | 就绪原因和周期记录 |
| GET | `/report` | 编辑、发布与费用统计 |
| POST | `/pause` | 请求体为 `{"paused":true}` 或 `{"paused":false}` |
| POST | `/run` | 手动执行一轮 |
| POST | `/recover` | 恢复发布租约和编辑任务，不调用外部接口 |

### 采集与编辑

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/sources` | 来源准入状态 |
| POST | `/sources/{id}/state` | 请求体为 `{"enabled":true}` 或 `{"enabled":false}` |
| GET | `/ingestion` | 轮询与快照进度 |
| POST | `/ingestion/poll` | 仅采集 |
| GET | `/ingestion/inbox` | 待办状态，不返回正文 |
| POST | `/ingestion/inbox/{id}/retry` | 重新排队有效的失败或挂起项 |
| GET | `/events` | 编辑版本与决策 |
| GET | `/editor-jobs` | 模型任务 |
| POST | `/editor-jobs/{id}/resolve-cost` | 人工核对费用 |
| POST | `/editor-jobs/{id}/retry` | 申请新的编辑请求 |

### 发布

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/publications` | 发布记录 |
| GET | `/publications/{id}/preview` | 实际投递文本预览 |
| GET | `/publications/{id}/attempts` | 发送尝试 |
| GET | `/publications/{id}/resolutions` | 人工核对记录 |
| POST | `/publications/{id}/dispatch` | 处理指定任务，服从全部检查 |
| POST | `/publications/{id}/resolve` | 核对 UNKNOWN 投递 |
| GET | `/alerts` | 发布告警 |

### 日报告警与演示

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/digests` | 日报记录 |
| POST | `/digests/run` | 检查并生成到期日报 |
| GET | `/automation/alerts` | 自动流程告警 |
| GET | `/notifications` | Webhook 投递状态 |
| POST | `/notifications/flush` | 处理已启用的告警投递 |
| POST | `/demo` | OFFLINE 合成演示，提交 `scenario` |

`scenario` 可选 `VALID`、`REVISED`、`STALE`、`DISCOVERY`、`TITLE_ONLY`。
演示请求体格式为 `{"scenario":"VALID"}`，只允许在 OFFLINE 模式运行。
