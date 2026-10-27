# 配置与处理规则

[返回首页](../README.md) · [运维手册](operations.md)

## 配置入口

完整环境变量、默认值及模型配置统一在 [application.yml](../src/main/resources/application.yml) 维护。首段是公共默认值，后续用 `---` 分隔的文档通过 `spring.config.activate.on-profile` 按需启用，只声明差异；[.env.example](../.env.example) 是常用覆盖示例。`java -jar` 不自动读取 `.env`，需使用进程或 IDE 环境变量，修改后重启。Compose 转发声明的变量，未设置的值使用应用默认配置。

运行配置集中在这一份 YAML 中，原有 Profile 名称和启动参数保持不变。测试配置独立保留在 `src/test/resources/application-test.yml`，由测试的 `test` Profile 启用，不打入应用 JAR。

密钥只从环境变量读取：`OPENAI_API_KEY`、`DEEPSEEK_API_KEY`、`NEWS_TELEGRAM_BOT_TOKEN`、`NEWS_ALERT_WEBHOOK_TOKEN`、`NEWS_OPS_TOKEN`。运维令牌至少 32 字符。密钥不要写入 Git、日志、新闻 URL 或请求正文。

可选变量应保持未设置，或填写有效值。空字符串也会被转发，不能用来表示恢复默认值；这使容器与本机运行采用相同规则。

## 外部服务清单与代码边界

外部服务实现集中在 [integrations](../src/main/java/com/globalnewsflash/integrations)。配置仍统一在 `application.yml`，现有配置前缀、环境变量和 Profile 不变；MySQL / H2 属于持久化依赖，继续由数据库配置管理。表中的状态是仓库默认值，实际启用情况还受环境变量、来源状态和全局暂停影响。

| 服务 | 请求与用途 | 主要配置 | 默认启用情况 |
| --- | --- | --- | --- |
| OpenAI | `POST /v1/responses`，结构化新闻编辑 | `news.openai`；`OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL` | 基础配置选择 OpenAI；OFFLINE 使用合成编辑器 |
| DeepSeek | `POST /chat/completions`，JSON 新闻编辑 | `news.deepseek`；`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` | `live` 默认选择；`NEWS_AI_PROVIDER` 可切换供应商 |
| Telegram | `getMe` / `getChat` / `getChatMember` 预检，`sendMessage` / `editMessageText` 发布与修订 | `news.telegram`；`NEWS_TELEGRAM_ENABLED`、`NEWS_TELEGRAM_BOT_TOKEN`、`NEWS_TELEGRAM_CHAT_ID` | 默认关闭；LIVE、外部访问和发送开关均满足后仍需通过发布检查 |
| Fed | GET RSS 列表，再 GET 公告 HTML 正文 | `news.ingestion.fed`；`NEWS_FED_URL`、`NEWS_FED_ARTICLE_SELECTOR`、`NEWS_FED_*` 准入配置 | `live` 默认启用与批准；自动采集调度仍默认关闭 |
| USGS | GET 地震 GeoJSON feed | `news.ingestion.usgs`；`NEWS_USGS_URL`、`NEWS_USGS_*` 准入配置 | 默认关闭，需单独启用与批准 |
| GDELT | GET 配置的新闻发现 JSON 地址 | `news.ingestion.gdelt`；`NEWS_GDELT_URL`、`NEWS_GDELT_*` 准入配置 | 默认关闭，仅作发现 |
| RSS / Atom | GET 配置的 feed，按需 GET 允许域名下的正文网页 | `news.ingestion.rss-feeds-json`；`NEWS_RSS_FEEDS_JSON` | 默认为空；每个来源单独配置与批准 |
| 告警 Webhook | POST 告警 JSON，可选 Bearer 鉴权 | `news.alert`；`NEWS_ALERT_ENABLED`、`NEWS_ALERT_WEBHOOK_URL`、`NEWS_ALERT_WEBHOOK_TOKEN` | 默认关闭；仅在 LIVE 且允许外部访问时投递 |

RSS 和网页抓取同样属于外部 HTTP 依赖。默认 OFFLINE 不调用这些服务；切换到 `live` 也不会在应用启动时自动调用模型或发送消息。上述清单说明代码支持的接入，并不代表真实账号、权限、额度和远端服务已完成验收；本地测试使用模拟 HTTP 客户端与合成数据，不会发起真实 AI、Telegram 或 Webhook 请求。

| 接入代码 | 职责 | 业务侧保留的职责 |
| --- | --- | --- |
| `integrations/ai` | 提供商选择、密钥与模型配置、请求封装、usage 和成稿解析；实现 `EditorPort` | `editorial` 的证据校验；`automation` 的预算、编辑任务与调度 |
| `integrations/sources` | feed / 正文下载、协议解析、内容提取，通过 `LiveSourceCollector` 提供采集入口 | `ingestion` 的来源配置与准入、租约、快照进度、待办与持久化 |
| `integrations/telegram` | Bot API、频道权限检查、消息格式、投递结果映射；实现 `DeliveryGateway` | `publication` 的队列、频率限制、结果未知状态与人工核对 |
| `integrations/webhook` | 单次 HTTP 投递、鉴权、超时处理；实现 `NotificationGateway` | `automation` 的告警待办、领取、退避与最多 5 次尝试 |
| `integrations/http` | 有大小限制的响应读取，供 AI、采集和 Telegram 复用 | 各接入自己的状态码、失败含义与重试决定 |

新增供应商时，在对应接入包中实现业务接口并添加模拟请求测试；配置仍加入 `application.yml`，按需补充 `.env.example` 和 Compose 环境变量转发，同时更新此清单。接入包采用包级组织，不增加独立部署服务或 Maven 子工程。

公共 HTTP 工具不自动重试：Telegram 超时可能发生在远端已发送之后，AI 超时可能已经计费；这些不确定结果必须保留给现有业务流程处理。采集的 HTTPS、域名与响应大小限制继续由来源接入维护，Webhook 接收端仍需按告警 `id` 去重。

## 运行模式

| Profile | 数据库与用途 |
| --- | --- |
| 默认 / `offline` | 本地 H2 文件 `data/news`，默认 OFFLINE |
| `offline,demo` | H2 加固定演示时钟，仅供合成数据验证 |
| `mysql` | 本地 MySQL，默认 OFFLINE |
| `mysql,live` | MySQL 加真实流程初始配置 |
| `offline,live` | H2 加真实流程初始配置，不能用于证明 MySQL 兼容性 |

`offline` 这个 Profile 名称保留以兼容现有命令，其配置分段只选择 H2 数据库；是否允许真实外部访问由 `news.runtime.mode` 和 `news.runtime.external-enabled` 控制。数据库 Profile 选择 `offline` 或 `mysql` 其中一个；同一文件中的配置分段按文档顺序覆盖。

`live` 默认启用 LIVE、外部访问和 Fed 采集，选择 DeepSeek；每来源处理 3 条、每轮编辑 1 条、`policy-ready=true`。其余模式默认选择 OpenAI。环境变量可覆盖配置中的占位值；`live` 的模式固定为 LIVE。启动仅注册来源，自动任务、Telegram 发送、额外发布 worker、日报、告警仍默认关闭。LIVE 拒绝固定演示时钟，因此不要与 `demo` 混用。

手动采集使用 `POST /ops/ingestion/poll`，随后查看 `GET /ops/ingestion/inbox`；这一步不调用 AI，也不要求解除全局暂停。编辑前检查 `/ops/status` 和 `/ops/automation` 返回的 readiness 原因。

全局暂停存储在数据库，初始为 true，重启保留。只有 `/ops/pause` 显式修改；配置密钥和开启调度不会解除暂停。

## AI 与预算

`NEWS_AI_PROVIDER` 选择 `openai` 或 `deepseek`，重启生效。两者使用独立密钥与配置，不自动切换供应商。OpenAI 使用 Responses API 的结构化输出；DeepSeek 使用 Chat Completions JSON 模式。请求和提示词由各适配器维护，共用配置校验、限长传输和成稿解析。

模型、单价、输入输出限制分别在 `news.openai` / `news.deepseek` 下。更换模型时同时更新两项价格；API 地址必须为 HTTPS。仓库价格是固定的预算估算配置，使用前按供应商账单和官方价格核对。1 美元 = 1,000,000 微美元，单价字段的单位为“微美元 / 百万 token”。

| 变量 | 基础默认值 / 含义 |
| --- | --- |
| `NEWS_DAILY_BUDGET_MICROS` / `NEWS_DAILY_REQUESTS` | UTC 日预算 1,000,000 微美元 / 100 次预占 |
| `NEWS_MAX_AGE` | 12h；重新发现不刷新新闻时效 |
| `NEWS_MAX_CHARACTERS` | 3000，包含来源和时间 |
| `NEWS_MINIMUM_INDEPENDENT_CHAINS` | 1，允许通过准入的单源内容 |
| `NEWS_POLICY_VERSION` | initial-v1；编辑规则改变时更新 |
| `NEWS_AUTOMATION_ENABLED` / `NEWS_AUTOMATION_DELAY_MS` | false / 60000；一轮采集、编辑、入队与发布 |
| `NEWS_REVISION_MODE` | HOLD；可显式选择 EDIT / UPDATE / CORRECTION |

预算和编辑任务先提交，再发起模型请求；数据库事务不跨越 HTTP 调用。预算按输入字节与最大输出 token 保守预占，拿到 usage 后结算；`spentMicros` 是内部估算，并非账单。已创建的每日预算上限不可原地变更，避免重启重置额度。

来源文本视为不可信数据，模型无浏览工具。返回内容需通过来源引用、原文 quote、数字、风险和长度检查；这些检查不能证明语义正确。高风险或证据不足的内容挂起。

## 新闻来源

每个真实来源必须独立配置 enabled、approved、许可记录和生产者允许列表。`licenseVersion` 是审核记录标识，不能为 `pending`。来源准入在采集和发送前均复查。

- **Fed**：`live` 配置采用 `fed-board-announcement-text-2026-10-26`，生产者为 `federal-reserve-board`，范围限 Board 公告正文，保留署名与原文链接；不含第三方材料、图片、标识和附件。相关原始参考：[转载说明](https://www.federalreserve.gov/disclaimer.htm)、[RSS 目录](https://www.federalreserve.gov/feeds/feeds.htm)。默认正文选择器 `#article`，找不到正文会失败。
- **USGS**：默认 significant_day GeoJSON；通过 `NEWS_USGS_*` 独立开启与批准。允许的生产者须对应 feed 中的 `net`，不能假定 `us` 覆盖整个 feed。
- **GDELT**：仅作发现，不能通过开启开关把未批准媒体转换成发布证据。
- **其他 RSS / Atom**：通过 `NEWS_RSS_FEEDS_JSON` 声明，结构如下；示例域名和权限记录需自行替换。

```json
[{"id":"approved-world-feed","url":"https://news.example.org/feed.xml","enabled":true,"approved":true,"licenseVersion":"reviewed-permission-record","producer":"example-newsroom","permittedProducers":["example-newsroom"],"allowedHosts":["news.example.org"],"articleSelector":"article","topic":"WORLD","fetchArticle":true}]
```

只有 feed 中的正文或允许域名的页面正文形成证据，标题、description 和 summary 不会自动当成详细事实。抓取限制主机、DNS、完整响应时间和大小，不自动跟随重定向。

采集保存快照和分页 offset，每批待办持久化后推进 offset，整个快照完成后才更新 ETag、Last-Modified 和 cursor。`NEWS_INGESTION_POLL_DELAY_MS` 控制未完成快照的后续批次延迟，`NEWS_SOURCE_POLL_INTERVAL_MS` 控制常规来源轮询间隔；调度由主自动流程驱动。cursor 无法补回已经从 feed 删除的历史新闻。

来源被操作员禁用后，修改 YAML 不会自动恢复；需用 `/ops/sources/{id}/state` 显式启用。移除配置或设置 enabled=false 会禁用来源。

## 发布、日报与告警

`NEWS_TELEGRAM_CHAT_ID` 可覆盖 `news.telegram.chat-id` 中的私有测试频道。真实发送需 `NEWS_TELEGRAM_ENABLED=true`，发送前检查 Bot 身份、目标频道及管理员权限。

发布限额默认每小时 6 次、每日 24 次、最小间隔 5 分钟，按 UTC 固定窗口统计真实发送尝试。额外 worker 默认关闭；主自动流程也会处理队列。修订默认 HOLD；EDIT / UPDATE / CORRECTION 只关联已确认送达的原消息。

日报默认关闭；开启 `NEWS_DIGEST_ENABLED` 后，默认 UTC 22 时从最近 24 小时有效已发布稿件选择至多 5 条、3000 字符，不引入新事实或模型费用，按日期和频道去重并在发送前复验。

告警默认关闭；`NEWS_ALERT_ENABLED` 和 HTTPS 的 `NEWS_ALERT_WEBHOOK_URL` 启用 webhook，可选 `NEWS_ALERT_WEBHOOK_TOKEN`。持久待办最多退避尝试 5 次，至少一次投递可能重复，接收端按 `id` 去重。消息只包含错误码和标识。

超时、批量和租约的完整参数见 application.yml；租约须覆盖对应请求与处理时间。费用不确定和投递不确定的人工核对见[运维手册](operations.md#异常与恢复)。
