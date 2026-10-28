# 运行配置

[首页](../README.md) · **配置** · [运维](operations.md) · [架构](architecture.md) · [模块](modules.md)

[配置入口](#配置入口) · [运行模式](#运行模式) · [数据库](#数据库) · [外部服务](#外部服务) · [处理规则](#处理规则与限额)

## 配置入口

| 入口 | 负责什么 |
| --- | --- |
| [application.yml](../src/main/resources/application.yml) | 完整参数和默认值，按 Profile 声明差异 |
| [.env.example](../.env.example) | 常用环境变量示例，不保存真实密钥 |
| 进程 / IDE 环境变量 | 覆盖配置中的占位值，修改后重启生效 |

`application.yml` 首段提供公共默认值，后续文档按 Profile 叠加差异。测试配置位于 `src/test/resources/application-test.yml`，不打入运行 JAR。

`java -jar` 不自动加载 `.env`，需要把变量传给启动进程或 IDE。Docker Compose 会读取 `.env` 并转发已声明的变量。
可选变量不用时保持未设置；空字符串会作为覆盖值传入，不会恢复默认值。

## 运行模式

| Profile | 数据库 | 用途 |
| --- | --- | --- |
| 默认 / `offline` | H2 | 本地运行，默认 OFFLINE |
| `offline,demo` | H2 | 固定时钟下的合成演示 |
| `mysql` | MySQL | 本地开发，默认 OFFLINE |
| `mysql,live` | MySQL | 真实采集、编辑与发布 |
| `offline,live` | H2 | 联调真实接入，不代表 MySQL 验证通过 |

`offline`、`mysql` 负责选择数据库，二者选择一个；`live` 叠加 LIVE 模式和外部流程配置。
`offline` 这个名称本身不禁止外部调用，例如 `offline,live` 仍会使用真实接入。固定演示时钟不能用于 LIVE，因此 `live` 不与 `demo` 混用。

### live 的默认行为

- 选择 DeepSeek，启用已配置的 Fed 来源；每来源处理 3 条，每轮编辑 1 条。
- 自动任务和 Telegram 真实发送开启，运营策略设为 `policy-ready=true`。
- 启动 10 秒后开始调度检查，每轮结束后间隔 60 秒。
- 额外发布 worker、日报和告警关闭；主自动流程负责处理发布队列。

这些是 Profile 提供的默认组合；环境覆盖项和启动参数仍需一起核对，实际状态以 `/ops/status` 为准。

### 执行开关速查

| 环境变量 | 基础默认值 | live 默认值 |
| --- | --- | --- |
| `NEWS_INGESTION_ENABLED` | 关闭 | 开启 |
| `NEWS_AUTOMATION_ENABLED` | 关闭 | 开启 |
| `NEWS_TELEGRAM_ENABLED` | 关闭 | 开启 |
| `NEWS_PUBLICATION_WORKER_ENABLED` | 关闭 | 关闭 |
| `NEWS_DIGEST_ENABLED` | 关闭 | 关闭 |
| `NEWS_ALERT_ENABLED` | 关闭 | 关闭 |

设置相应环境变量为 `true` / `false` 可覆盖默认值，修改后重启生效。

全局暂停不属于上表中的开关，而是数据库状态：初始为 `true`，重启保留上次值。主自动流程需要配置就绪且暂停已解除；控制方法见[运行控制](operations.md#运行控制)。

## 数据库

本地 MySQL 由根目录 [compose.yml](../compose.yml) 管理，业务表由应用启动时执行 Flyway 迁移创建。

| 项目 | 本地默认值 |
| --- | --- |
| 本机连接地址 | `127.0.0.1:3307` |
| 应用容器连接地址 | `db:3306` |
| 数据库 / 应用用户 | `global_news_flash` |
| 应用账号 / root 密码 | `123456`，仅为本地开发默认值 |

`NEWS_DB_URL`、`NEWS_DB_USER`、`NEWS_DB_PASSWORD` 覆盖应用连接；`NEWS_DB_ROOT_PASSWORD` 覆盖容器初始化密码。已有账号的密码需在 MySQL 中修改，修改环境变量不能更新已有数据目录中的账号。

H2 模式使用项目下的 `data/news` 文件库。MySQL 的数据位置和恢复注意事项见[Docker 与数据](operations.md#docker-与数据)。

## 外部服务

### AI 编辑

`NEWS_AI_PROVIDER` 选择 `openai` 或 `deepseek`：基础配置选择 OpenAI，`live` 默认选择 DeepSeek，不自动切换供应商。

| 供应商 | 密钥 | 模型与地址覆盖项 |
| --- | --- | --- |
| DeepSeek | `DEEPSEEK_API_KEY` | `DEEPSEEK_MODEL`、`DEEPSEEK_BASE_URL` |
| OpenAI | `OPENAI_API_KEY` | `OPENAI_MODEL`、`OPENAI_BASE_URL` |

模型、单价、输入输出限制分别维护在 `news.deepseek`、`news.openai` 中。更换模型时同时核对两项价格；配置中的费用是预算估算，不代表账单金额。

每个供应商分别声明输入、输出两项价格，单位为“微美元 / 百万 token”。输入字符上限、输出 token 上限与超时也独立配置；模型响应的 usage 用于结算实际估算费用。预算规则见[预算](#预算)。

### Telegram

| 配置 | 作用 |
| --- | --- |
| `NEWS_TELEGRAM_BOT_TOKEN` | Bot 令牌，必须通过环境变量提供 |
| `NEWS_TELEGRAM_CHAT_ID` | 覆盖 `news.telegram.chat-id` 中的私有测试频道 |

Bot 需要目标频道的管理员及发布权限。发送前会检查 Bot 身份、目标频道和权限；执行开关、全局暂停、预算和证据复验仍会约束投递。

### 新闻来源

每个真实来源都有独立的准入声明：

- `enabled`：是否启用该来源。
- `approved`：是否已批准用于当前流程。
- `license-version`：许可审核记录标识，不能为 `pending`。
- `permitted-producers`：允许作为证据的生产者列表。

启用来源不等于批准使用；采集与发送前都会检查准入状态。

| 来源 | 当前用途与配置 |
| --- | --- |
| Fed | `live` 默认启用，配置位于 `news.ingestion.fed` |
| USGS | 默认关闭，通过 `NEWS_USGS_*` 单独启用和批准 |
| GDELT | 默认关闭，仅用于发现，不能把未批准媒体作为发布证据 |
| RSS / Atom | 通过 `NEWS_RSS_FEEDS_JSON` 配置，默认为空 |

USGS 的生产者允许列表须匹配 feed 中的 `net`，不能假定 `us` 覆盖所有生产者。

#### Fed 的当前配置

- 许可记录：`fed-board-announcement-text-2026-10-26`。
- 允许的生产者：`federal-reserve-board`。
- 内容范围：Board 公告正文，保留署名和原文链接；不包含第三方材料、图片、标识和附件。
- 正文选择器：默认 `#article`，找不到正文会失败。

审核参考：[转载说明](https://www.federalreserve.gov/disclaimer.htm)、[RSS 目录](https://www.federalreserve.gov/feeds/feeds.htm)。

#### 添加 RSS / Atom

把以下数组作为 `NEWS_RSS_FEEDS_JSON` 的 JSON 字符串提供；示例域名和许可记录需要替换为实际审核结果。

```json
[
  {
    "id": "approved-world-feed",
    "url": "https://news.example.org/feed.xml",
    "enabled": true,
    "approved": true,
    "licenseVersion": "reviewed-permission-record",
    "producer": "example-newsroom",
    "permittedProducers": ["example-newsroom"],
    "allowedHosts": ["news.example.org"],
    "articleSelector": "article",
    "topic": "WORLD",
    "fetchArticle": true
  }
]
```

只有 feed 正文或允许域名下的网页正文形成证据，标题和摘要不会自动当作详细事实。抓取会限制主机、DNS 解析结果、响应大小和超时，不自动跟随重定向。

来源被操作员禁用后，修改 YAML 不会自动恢复；需要显式调用来源状态接口。移除配置或设置 `enabled=false` 会禁用来源。

### 日报与告警

- `NEWS_DIGEST_ENABLED=true` 开启日报：默认 UTC 22 时，从最近 24 小时有效已发布稿件选取至多 5 条、3000 字符，不增加模型费用。
- `NEWS_ALERT_ENABLED=true` 开启告警：还需配置 HTTPS 的 `NEWS_ALERT_WEBHOOK_URL`，可选 `NEWS_ALERT_WEBHOOK_TOKEN`。最多尝试 5 次，重试可能造成重复投递，接收端按告警 `id` 去重。

## 处理规则与限额

### 采集与编辑

| 环境变量 | 默认值或规则 |
| --- | --- |
| `NEWS_MAX_AGE` | 12 小时；重新发现不刷新事实时间 |
| `NEWS_MAX_CHARACTERS` | 最多 3000 字符，包含来源和时间 |
| `NEWS_MINIMUM_INDEPENDENT_CHAINS` | 至少 1 条独立证据链 |
| `NEWS_ALLOWED_TOPICS` | 允许题材见 `news.automation.allowed-topics`，范围外内容挂起 |
| `NEWS_POLICY_READY` | 基础配置为 false，live 为 true |
| `NEWS_POLICY_VERSION` | 默认 `initial-v1`；修改编辑策略时更新，便于追踪 |

采集保存不可变快照和分页进度，整批待办提交后才推进游标；完整快照处理后才更新 feed 的 ETag、Last-Modified 和 cursor，无法补回已从 feed 消失的历史内容。
常规轮询间隔由 `NEWS_SOURCE_POLL_INTERVAL_MS` 控制，未完成快照的后续批次由 `NEWS_INGESTION_POLL_DELAY_MS` 控制。

来源文本作为不可信输入处理，模型无浏览工具；高风险、证据不足或校验失败的稿件会挂起。

### 预算

| 环境变量 | 默认值 |
| --- | --- |
| `NEWS_DAILY_BUDGET_MICROS` | UTC 日内 1,000,000 微美元 |
| `NEWS_DAILY_REQUESTS` | UTC 日内 100 次请求预占 |

1 美元 = 1,000,000 微美元。请求前按保守估算预占，取得 usage 后结算；已创建日预算的上限不能通过修改配置或重启重置。
超时、未知 usage 或实际费用超出预占的情况会保留预算，按[费用核对](operations.md#费用与投递核对)处理。

### 发布与修订

发布频率默认每小时 6 次、每日 24 次，最小间隔 5 分钟，按 UTC 统计真实发送尝试。
对应覆盖项为 `NEWS_PUBLICATION_MAX_PER_HOUR`、`NEWS_PUBLICATION_MAX_PER_DAY` 和 `NEWS_PUBLICATION_MINIMUM_INTERVAL`。

`NEWS_REVISION_MODE` 默认 `HOLD`。选择 `EDIT`、`UPDATE` 或 `CORRECTION` 时，只能关联已确认送达的原消息；投递结果不明时先核对，不自动重发。

## 密钥与运维访问

密钥仅通过环境变量提供，不写入仓库、日志、新闻 URL 或请求正文。
`NEWS_OPS_TOKEN` 与外部服务密钥独立，至少 32 个字符；未配置时禁用运维访问，非空但不足 32 个字符会导致启动失败。
