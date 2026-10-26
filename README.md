# Global News Flash

独立的英文 Telegram 新闻后端，Java 21 / Spring Boot / MyBatis。保留离线演示，新增显式启用的真实采集、OpenAI 编辑、Telegram 发布、周期任务、日报和告警接口。

**当前状态：本机 MySQL 健康检查及后端启动已通过，Fed 已成功采集并入库 3 条含正文的待处理内容。XML BOM 兼容修复通过 18 项采集定向测试，实际 RSS 的 20 条元数据可正常解析。OpenAI 试请求返回 API 余额不足，按要求暂停后续真实 AI 调用；Telegram 权限与发送、英文成稿、新闻质量、成本及长期运行仍待联调，尚未部署。**

## 环境变量

完整名称和默认值见 [.env.example](.env.example)。应用读取进程或容器环境变量；`java -jar` 不会自动加载 `.env` / `.env.example`。修改环境变量后需重启。不要将密钥提交到 Git 或放进聊天、新闻来源 URL、日志。

### 真实流程配置

密钥通过环境变量提供。首次手动采集使用 `mysql,live` Profile；`application-live.yml` 已设置 LIVE、外部访问、采集和美联储来源，首批每次最多处理 3 条。下表的运行开关可用环境变量覆盖，无需逐项新建 Windows 用户变量。自动任务、运营策略确认、Telegram 发送、日报和告警仍默认关闭。

| 变量 | 用途 |
| --- | --- |
| `NEWS_RUNTIME_MODE=LIVE` | 真实模式，默认 OFFLINE |
| `NEWS_EXTERNAL_ENABLED=true` | 允许外部客户端，默认 false |
| `NEWS_OPS_TOKEN` | 至少 32 字符的独立运维令牌 |
| `OPENAI_API_KEY` | OpenAI API 密钥 |
| `NEWS_TELEGRAM_BOT_TOKEN` | 具有目标频道管理员权限的 Bot token |
| `NEWS_INGESTION_ENABLED=true` | 启用真实采集 |
| `NEWS_POLICY_READY=true` | 确认已检查运营参数 |

模型和单价属于非敏感配置，直接在 `src/main/resources/application.yml` 的 `news.openai` 下维护，无需新建用户环境变量。当前默认 `gpt-6-luna`，每百万输入/输出 token 分别为 `100000` / `500000` 微美元（2026-10-26 标准短上下文基础价格）。更换模型时同时更新两项单价，并确认账户具备相应访问权限。

测试频道 ID 也在同一文件的 `news.telegram.chat-id` 下维护，当前默认 `-1004482990261`，来自已创建私有测试频道的消息链接。`NEWS_TELEGRAM_CHAT_ID` 仅用于可选覆盖，无需新建环境变量；更换频道时更新此配置。频道 ID 已填写不代表 Bot 权限已通过 API 验证，真实发送仍默认关闭。

1 美元 = 1,000,000 微美元。价格字段填写“每百万 token 的美元价格 × 1,000,000”。按所选模型的 [OpenAI 官方价格](https://developers.openai.com/api/docs/pricing) 更新；价格缺失或为零会阻止请求。`OPENAI_MODEL`、`NEWS_OPENAI_INPUT_MICROS_PER_MILLION_TOKENS`、`NEWS_OPENAI_OUTPUT_MICROS_PER_MILLION_TOKENS` 仍可作为可选环境覆盖；已有环境值优先于配置中的默认值。Compose 未提供这些环境值时也使用同一份 application.yml 默认值。

另需至少一个明确启用、批准并记录许可和生产者的来源：

`live` Profile 已配置美联储公告文本，准入记录为 `fed-board-announcement-text-2026-10-26`，生产者为 `federal-reserve-board`。已核对 [官方转载说明](https://www.federalreserve.gov/disclaimer.htm) 和 [RSS 目录](https://www.federalreserve.gov/feeds/feeds.htm)：保留来源署名与原文链接，范围限 Board 公告正文，不含第三方材料、图片、标识或链接附件。其余来源仍需分别配置。

| 来源 | 必需变量 |
| --- | --- |
| USGS | `NEWS_USGS_ENABLED=true`、`NEWS_USGS_APPROVED=true`、`NEWS_USGS_LICENSE_VERSION`、`NEWS_USGS_PERMITTED_PRODUCERS` |
| Fed | `NEWS_FED_ENABLED=true`、`NEWS_FED_APPROVED=true`、`NEWS_FED_LICENSE_VERSION`、`NEWS_FED_PERMITTED_PRODUCERS=federal-reserve-board` |
| 其他 RSS/Atom | `NEWS_RSS_FEEDS_JSON`，每个来源独立声明 enabled / approved / licenseVersion / permittedProducers / allowedHosts |

`licenseVersion` 是核对来源权限后的记录标识，不能继续填 `pending`。USGS 的 `net` 有多个生产者，允许列表须对应确认准入的生产者，不能假定 `us` 覆盖整个 feed。填写模型和 Bot 密钥不会批准新闻来源。

### 开关与初始参数

| 变量 | 默认值及行为 |
| --- | --- |
| `NEWS_AUTOMATION_ENABLED` | false；true 后每分钟尝试采集、编辑、入队和发布 |
| `NEWS_TELEGRAM_ENABLED` | false；false 时保留真实稿件队列供预览，不发送 |
| `NEWS_PUBLICATION_WORKER_ENABLED` | false；可额外每 5 秒处理发布队列；主流程也处理队列 |
| `NEWS_MAX_AGE` | 12h，重新发现不会让旧新闻变新 |
| `NEWS_MAX_CHARACTERS` | 3000，包含来源和时间 |
| `NEWS_ALLOWED_TOPICS` | EARTHQUAKE,OFFICIAL_ANNOUNCEMENT,WORLD,ECONOMY,SCIENCE,TECHNOLOGY |
| `NEWS_MINIMUM_INDEPENDENT_CHAINS` | 1，允许符合门禁的单源内容 |
| `NEWS_DAILY_BUDGET_MICROS` / `NEWS_DAILY_REQUESTS` | 1000000 / 100，即 UTC 日预算 1 美元、100 次模型请求预占 |
| `NEWS_PUBLICATION_MAX_PER_HOUR` / `NEWS_PUBLICATION_MAX_PER_DAY` | 6 / 24，UTC 固定窗口内真实发送尝试上限 |
| `NEWS_PUBLICATION_MINIMUM_INTERVAL` | 5m，频道发送尝试的最小间隔 |
| `NEWS_REVISION_MODE` | HOLD；可明确选择 EDIT / UPDATE / CORRECTION |
| `NEWS_POLICY_VERSION` | initial-v1，改变编辑规则时更新版本 |

这些是保守初始参数，未经真实运营验证。预算预占按输入字节与最大输出 token 上限计算，可能提前阻止请求；实际成本按 usage 和配置单价结算。已创建的每日预算期间上限不可原地变更，避免重启意外重置已花费额度。

数据库全局暂停默认 **true**，重启保留，自动任务不会自行解除。用受保护的 `/ops/pause` 显式放行。开启 scheduler、填写密钥不会重置暂停。

可选配置：

- `OPENAI_BASE_URL`：默认 `https://api.openai.com/v1`，只接受 HTTPS，代码追加 `/responses`。
- `NEWS_DIGEST_ENABLED=true`：启用每日汇总；`NEWS_DIGEST_UTC_HOUR=22`、`NEWS_DIGEST_MAX_ITEMS=5`、`NEWS_DIGEST_MAX_CHARACTERS=3000`。
- `NEWS_ALERT_ENABLED=true`、`NEWS_ALERT_WEBHOOK_URL`：向指定 HTTPS webhook 发送通用 JSON 告警；可加 `NEWS_ALERT_WEBHOOK_TOKEN` Bearer token，接收端按 `id` 去重。
- MySQL：本地连接配置已在 `application-mysql.yml` 中设置，应用账号和 root 默认密码均为 `123456`。`NEWS_DB_URL`、`NEWS_DB_USER`、`NEWS_DB_PASSWORD` 可选覆盖后端配置；`NEWS_DB_ROOT_PASSWORD` 可选覆盖容器的 root 初始密码。
- 高级超时、批量与租约见 [.env.example](.env.example)。租约须覆盖对应请求与处理时间。

## 新闻来源

USGS 默认 significant_day GeoJSON。Fed 拉取 RSS 后读取官方正文，默认 `#article` 选择器，可用 `NEWS_FED_ARTICLE_SELECTOR` 调整；已验证首批 3 条正文提取和入库。找不到正文会记录失败，不以标题代替证据。

`NEWS_RSS_FEEDS_JSON` 是 JSON 数组，下面仅为结构示例，需替换域名和权限记录：

```json
[{"id":"approved-world-feed","url":"https://news.example.org/feed.xml","enabled":true,"approved":true,"licenseVersion":"your-reviewed-permission-record","producer":"example-newsroom","permittedProducers":["example-newsroom"],"allowedHosts":["news.example.org"],"articleSelector":"article","topic":"WORLD","fetchArticle":true}]
```

支持 RSS 2.0 / Atom。只有 content:encoded / Atom content 正文，或允许域名的页面正文才能形成来源材料；description / summary / 标题不会自动转成详细事实。请求限制主机、大小、DNS 与完整响应时间，不自动跟随重定向。GDELT 仍只用于发现，不自动发布未批准媒体。

采集保存 feed 快照与分页 offset，每批 inbox 持久化后推进 offset；整个快照处理完才提交 ETag / Last-Modified / cursor。材料版本去重、暂时失败退避，修订保存新版本。cursor 不能补回来源已从 feed 删除的历史新闻。

首次注册采用显式配置；之后操作员在数据库禁用的来源不会自动恢复。移除配置或 enabled=false 会禁用来源；批准/许可/生产者变更会更新当前规则，发送前重新检查。

## 处理与发布

真实流程：采集待办 → 来源/主题/时效/风险门禁 → 提交模型任务和预算预占 → OpenAI 英文编辑 → 引用/quote/数字检查 → 成稿与队列原子提交 → 发送前复验 → Telegram。

OpenAI 使用 [Responses Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)，输出英文摘要、事实引用、原文 quote、相关性和风险等级。没有模型浏览工具；来源文本是不可信数据。quote 存在、数字和结构校验不能证明语义正确，新闻判断和英文质量仍需真实样本验证。高风险材料挂起。

预算与 EDITING 任务在请求前提交，事务不跨越模型 HTTP 调用。401/429 等确定拒绝可退避重试；超时、中断、未知 usage 进入 UNCERTAIN，保留预算并停止自动重试。核对用量后可结算，再显式申请新请求。

Telegram 按 [Bot API](https://core.telegram.org/bots/api) 实现 sendMessage / editMessageText；发送前检查 getMe / getChat / getChatMember，确认频道与管理员权限。UPDATE / CORRECTION 有明确前缀并关联原消息。429 遵循 retry_after；发送超时、5xx、无法确认结果进入 UNKNOWN，不盲目重发。

修订默认 HOLD；选择 EDIT / UPDATE / CORRECTION 后只关联已确认送达的原消息。日报从最近 24 小时有效已发布稿件选择完整条目，不新增事实或模型费用；UTC 日期与频道去重，发送前复核来源、证据和时效。

可选 webhook 告警使用持久待办，最多 5 次退避尝试。至少一次投递可能重复，接收端按 `id` 去重。告警只含稳定错误码和标识，不含密钥或正文。

## 构建和运行

需要 Java 21。环境配置后再执行构建与验证：

```powershell
.\mvnw.cmd -B -ntp verify
```

离线演示：

```powershell
java -jar .\target\global-news-flash-0.1.0-SNAPSHOT.jar --spring.profiles.active=offline,demo --spring.main.web-application-type=none --news.demo.enabled=true
```

离线重启/HTTP smoke：`.\scripts\smoke.ps1 -SkipBuild`。LIVE 拒绝固定演示时钟。

首次手动采集使用已启动的本地 MySQL。在 IDE 将 Active profiles 从 `mysql` 改为 `mysql,live` 并重启后端；使用已构建 JAR 时等价命令为：

```powershell
java -jar .\target\global-news-flash-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql,live
```

重启只在本地注册来源，不自动抓取。通过受保护的 `POST /ops/ingestion/poll` 手动采集，返回结果后查看 `GET /ops/ingestion/inbox`。该步骤不调用 AI 或发帖，也无需解除全局暂停。确认来源正文后，再配置运营策略并启用编辑与发布。

`offline,live` 可选择本地 H2 文件数据库。不要同时加载 demo；`offline` 数据库 Profile 只选择 H2，客户端模式由 LIVE Profile / NEWS_RUNTIME_MODE 控制。已有环境变量优先于上述默认值。此前已被禁用的来源仍需通过 `/ops/sources/{id}/state` 显式开启，修改 YAML 不会自动解除禁用。

## 运维接口

默认 `127.0.0.1:8090`。`/actuator/health` 公开，其他路径需要 `X-Ops-Token`。未配置 NEWS_OPS_TOKEN 时运维入口拒绝访问。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/ops/status`、`/ops/automation` | 模式、开关、readiness 和周期记录 |
| POST | `/ops/pause` | `{"paused":true}` 或 false，全局开关、重启保留 |
| POST | `/ops/run` | 执行一轮真实流程，不自动解除暂停 |
| GET | `/ops/sources`、`/ops/ingestion` | 来源准入、游标、错误、快照进度 |
| POST | `/ops/sources/{id}/state` | `{"enabled":false}` 或 true，启用仍须批准 |
| POST | `/ops/ingestion/poll` | 仅采集，不调用 AI 或发布 |
| GET | `/ops/ingestion/inbox` | 最近待办与原因，无正文 |
| POST | `/ops/ingestion/inbox/{id}/retry` | 对准入有效的明确失败/挂起项重新排队 |
| GET | `/ops/events`、`/ops/editor-jobs` | 编辑版本和模型任务 |
| POST | `/ops/editor-jobs/{id}/resolve-cost` | `{"actualMicros":0,"note":"核对依据"}`，只结算不确定费用，不发布丢失响应 |
| POST | `/ops/editor-jobs/{id}/retry` | 对明确拒绝或完成成本核对的任务申请新编辑 |
| GET | `/ops/publications` | 待发布/已发布记录和正文预览 |
| GET | `/ops/publications/{id}/preview` | 包含 UPDATE / CORRECTION 前缀的实际发送文本 |
| GET | `/ops/publications/{id}/attempts`、`/ops/publications/{id}/resolutions` | 尝试与核对审计 |
| POST | `/ops/publications/{id}/dispatch` | 处理指定任务，服从所有门禁 |
| POST | `/ops/publications/{id}/resolve` | CONFIRMED_SENT / CONFIRMED_NOT_SENT，要求 note；确认送达还需 messageId |
| POST | `/ops/recover` | 恢复发布租约和模型任务，不调用外部接口 |
| GET / POST | `/ops/digests` / `/ops/digests/run` | 日报记录 / 检查是否到期生成 |
| GET | `/ops/alerts`、`/ops/automation/alerts`、`/ops/notifications` | 告警与 webhook 投递状态 |
| POST | `/ops/notifications/flush` | 处理已启用的 webhook 告警 |
| GET | `/ops/report` | 编辑/发布与成本统计 |
| POST | `/ops/demo` | 仅 OFFLINE 的合成场景 |

Telegram UNKNOWN 确认未发送转 FAILED，不自动重发；模型 UNCERTAIN 与 Telegram UNKNOWN 分别核对。

## 部署和恢复

### 本地 MySQL：Docker 数据库 + 本机 Spring Boot

根目录 `compose.yml` 默认只启动 MySQL 8.4，用法与 Trade 的本地基础设施一致。地址为 `127.0.0.1:3307`，库和用户均为 `global_news_flash`，数据目录为 `A:/DockerData/global-news-flash/mysql`，与 Trade 的端口和目录分开。

本地开发的应用账号和 root 默认密码均为 `123456`，无需额外配置数据库密码环境变量。打开 Docker Desktop，使用本机 Linux containers，然后执行：

```powershell
cd A:\explore\global_news_flash
docker compose up -d
docker compose ps
```

首次启动创建独立库和用户；等待状态变为 healthy。业务表由后续 Spring Boot 启动时执行 Flyway 迁移创建。查看日志和停止数据库：

```powershell
docker compose logs --tail 80 db
docker compose stop
```

数据保留在上述宿主机目录。已有数据库的密码不能仅通过修改环境变量更新，须在 MySQL 内修改对应用户密码，见 [MySQL 初始化规则](https://hub.docker.com/_/mysql)。

Spring Boot / DataGrip 连接参数：

| 参数 | 值 |
| --- | --- |
| Host / Port | `127.0.0.1` / `3307` |
| Database | `global_news_flash` |
| User | `global_news_flash` |
| Password | `123456`（应用账号和 root 相同） |

后端 IDE 的 Active profiles 设置为 `mysql`，连接地址、用户名和密码已在 `application-mysql.yml` 中配置。已有的 `NEWS_DB_PASSWORD`、`NEWS_DB_ROOT_PASSWORD` 环境变量会覆盖默认值；如旧终端或 IDE 仍保留之前的密码，请更新变量或重开进程。首次数据库联调不必启用 LIVE 或自动发帖。

### 可选：后端也放进 Docker

`compose.app.yml` 保存应用容器配置，合并后启动：

```powershell
docker compose -f compose.yml -f compose.app.yml up -d --build
```

应用容器连接 `db:3306`；本机后端连接 `127.0.0.1:3307`。两种方式连接同一个本项目 MySQL。应用容器非 root，运维端口仅回环映射，模型/采集/发帖默认仍关闭。完整容器启动还需 `NEWS_OPS_TOKEN`；Dockerfile 构建跳过测试，不能替代后续验证。

本机已验证数据库健康检查、后端启动和首批 Fed 正文采集入库；编辑与发布仍按上面的分阶段流程联调，当前自动任务和发送保持关闭。

### 备份和恢复

备份：`.\scripts\backup-mysql.ps1`，默认 `.tools/backups`，带 SHA256。恢复：先停止本机 IDE 后端和 app 容器，再显式执行 `.\scripts\restore-mysql.ps1 -BackupFile <绝对SQL路径>`；恢复后强制暂停并保留审计，不自动启动。脚本只能检测容器化 app，本机后端需自行停止。备份/恢复脚本仅作为可选工具，不参与日常启动，本批次未进行恢复演练。

## 当前边界

跨媒体语义聚类和独立事实核实尚未实现，以明确批准的单个来源事件为单位运行。真实模型质量、来源选择器和权限待验证。growth 仍是合成推广/邀请归因；真实推广分发、加入事件、阅读量/订阅变化和渠道效果尚未接通，演示数字不代表真实运营数据。

项目内维护当前功能与用法。规划、过程日志、研究和验收记录仍保存在桌面 `0/global-news-flash-archive`，按日期归档。
