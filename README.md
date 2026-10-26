# Global News Flash

独立的英文 Telegram 新闻后端，Java 21 / Spring Boot / MyBatis。保留离线演示，新增显式启用的真实采集、OpenAI 编辑、Telegram 发布、周期任务、日报和告警接口。

**当前状态：真实接入代码已补充，本批次按要求未编译、未测试、未调用新闻源/OpenAI/Telegram、未部署。下面描述的是代码能力；真实权限、新闻质量、成本、MySQL 迁移和长期运行仍待配置后验证。之前离线版本的测试结果不能作为本批次的验证结果。**

## 环境变量

完整名称和默认值见 [.env.example](.env.example)。应用读取进程或容器环境变量；`java -jar` 不会自动加载 `.env` / `.env.example`。修改环境变量后需重启。不要将密钥提交到 Git 或放进聊天、新闻来源 URL、日志。

### 真实流程必需配置

| 变量 | 用途 |
| --- | --- |
| `NEWS_RUNTIME_MODE=LIVE` | 真实模式，默认 OFFLINE |
| `NEWS_EXTERNAL_ENABLED=true` | 允许外部客户端，默认 false |
| `NEWS_OPS_TOKEN` | 至少 32 字符的独立运维令牌 |
| `OPENAI_API_KEY` | OpenAI API 密钥 |
| `OPENAI_MODEL` | 账户可用且支持 Responses structured outputs 的模型，没有隐含模型默认值 |
| `NEWS_OPENAI_INPUT_MICROS_PER_MILLION_TOKENS` | 所选模型每百万输入 token 的价格，单位为微美元 |
| `NEWS_OPENAI_OUTPUT_MICROS_PER_MILLION_TOKENS` | 所选模型每百万输出 token 的价格，单位为微美元 |
| `NEWS_TELEGRAM_BOT_TOKEN` | 具有目标频道管理员权限的 Bot token |
| `NEWS_TELEGRAM_CHAT_ID` | 频道 `-100...` 数字 ID 或 `@username`；首次使用私有测试频道 |
| `NEWS_INGESTION_ENABLED=true` | 启用真实采集 |
| `NEWS_POLICY_READY=true` | 确认已检查运营参数 |

1 美元 = 1,000,000 微美元。价格字段填写“每百万 token 的美元价格 × 1,000,000”；例如**仅作换算示例**：0.25 美元/百万 token 对应 `250000`，不代表任何模型的当前价格。按所选模型的 [OpenAI 官方价格](https://developers.openai.com/api/docs/pricing) 填写。价格缺失或为零会阻止请求。

另需至少一个明确启用、批准并记录许可和生产者的来源：

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
- MySQL：`NEWS_DB_URL`、`NEWS_DB_USER`、`NEWS_DB_PASSWORD`；Compose 数据库还需 `NEWS_DB_ROOT_PASSWORD`。
- 高级超时、批量与租约见 [.env.example](.env.example)。租约须覆盖对应请求与处理时间。

## 新闻来源

USGS 默认 significant_day GeoJSON。Fed 拉取 RSS 后读取官方正文，默认 `#article` 选择器，可用 `NEWS_FED_ARTICLE_SELECTOR` 调整。真实网页结构尚待联调；找不到正文会记录失败，不以标题代替证据。

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

首次真实联调使用本地文件数据库（先设置环境变量）：

```powershell
java -jar .\target\global-news-flash-0.1.0-SNAPSHOT.jar --spring.profiles.active=offline,live
```

专用 MySQL 改为 `mysql,live`，不要加载 demo。`offline` 数据库 profile 只选择 H2；客户端模式由 LIVE profile / NEWS_RUNTIME_MODE 控制。

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

已提供 Dockerfile / compose.yml，尚未运行部署验证。独立 MySQL 8.4 schema、用户和数据卷，不访问 Trade。应用非 root、运维端口仅回环映射。Compose 必填 NEWS_DB_PASSWORD、NEWS_DB_ROOT_PASSWORD、NEWS_OPS_TOKEN；其他变量从环境传入，默认外部开关关闭。

完成本地和专用 MySQL 验证后可执行 `docker compose up -d --build`。Dockerfile 构建跳过测试，不能替代 verify。

备份：`.\scripts\backup-mysql.ps1`，默认 `.tools/backups`，带 SHA256。恢复：先停止 app，再显式执行 `.\scripts\restore-mysql.ps1 -BackupFile <绝对SQL路径>`；恢复后强制暂停并保留审计，不自动启动。脚本本轮只做静态检查，未进行恢复演练。

## 当前边界

跨媒体语义聚类和独立事实核实尚未实现，以明确批准的单个来源事件为单位运行。真实模型质量、来源选择器和权限待验证。growth 仍是合成推广/邀请归因；真实推广分发、加入事件、阅读量/订阅变化和渠道效果尚未接通，演示数字不代表真实运营数据。

项目内维护当前功能与用法。规划、过程日志、研究和验收记录仍保存在桌面 `0/global-news-flash-archive`，按日期归档。
