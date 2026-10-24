# Global News Flash

独立的英文 Telegram 新闻系统。当前交付是 **离线开发版本**：已接通合成来源、证据与编辑规则、持久队列、模拟发布、推广归因及受保护运维入口；尚未接入真实来源采集、OpenAI 或 Telegram。

## 本地构建

需要 Java 21。Maven Wrapper 固定为 3.9.16，首次运行下载构建工具和依赖；无需全局安装 Maven。

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Spring Boot 固定 4.1.2，MyBatis Starter 固定 4.1.0。依赖版本在 [pom.xml](pom.xml)，兼容依据见 [Spring Boot 要求](https://docs.spring.io/spring-boot/system-requirements.html) 和 [MyBatis Starter 文档](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

## 运行完整离线演示

```powershell
java -jar .\target\global-news-flash-0.1.0-SNAPSHOT.jar --spring.profiles.active=offline,demo --spring.main.web-application-type=none --news.demo.enabled=true
```

结果行以 `SYNTHETIC_DEMO_RESULT=` 开头，消息 ID 以 `sim-` 开头。程序完成后退出，数据库保存在 `data/news.mv.db`。再次运行复用已有事件版本和消息 ID，`duplicate=true`，不增加发送尝试。

`demo` profile 固定测试时钟为 `2026-10-24T14:00:00Z`；48 小时、2000 字符及演示预算都是合成场景的测试输入。默认运行的正式运营策略仍未配置。这些数值不是已确定的运营参数。

CLI 演示只允许非 Web 模式且模拟 worker 关闭。它显式放行这次模拟流程，结束时设为暂停。Web 演示遵守当前暂停开关，不更改操作员的设置。

可复现的进程重启、HTTP 健康、鉴权、拒绝场景检查：

```powershell
.\scripts\smoke.ps1 -SkipBuild
```

脚本在 `.tools/smoke/` 创建独立测试数据库和日志，启动隐藏的本地 Java 进程并在结束时关闭；不连接模型、新闻源或 Telegram。

## 启动运维服务

在本地环境配置至少 32 字符的独立 `NEWS_OPS_TOKEN`，再运行：

```powershell
java -jar .\target\global-news-flash-0.1.0-SNAPSHOT.jar --spring.profiles.active=offline,demo
```

默认监听 `127.0.0.1:8090`，公开健康检查为 `/actuator/health`。未配置令牌时所有运维请求拒绝访问。运维请求使用 `X-Ops-Token` 请求头，没有默认账号或密码。`.env` 文件不会自动加载；可用进程环境变量或 Spring 的安全外部配置。

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| GET | `/ops/status` | 离线模式、暂停、编辑提供者和策略状态 |
| GET | `/ops/sources`、`/ops/events`、`/ops/publications` | 来源与证据版本、发布记录 |
| GET | `/ops/publications/{id}/attempts`、`/ops/publications/{id}/resolutions` | 尝试与核对审计 |
| GET | `/ops/alerts`、`/ops/report` | 本地异常及合成运营汇总 |
| POST | `/ops/pause` | `{"paused":true}` 或 `false` |
| POST | `/ops/sources/{id}/state` | `{"enabled":false}`；真实来源不能启用 |
| POST | `/ops/demo` | `{"scenario":"VALID"}`，或 `STALE`、`DISCOVERY`、`TITLE_ONLY`、`REVISED` |
| POST | `/ops/publications/{id}/dispatch`、`/ops/recover` | 显式模拟投递与租约恢复 |
| POST | `/ops/publications/{id}/resolve` | 核对 UNKNOWN；要求 `resolution`、`note`，确认送达还需 `messageId` |

核对取值为 `CONFIRMED_SENT` 或 `CONFIRMED_NOT_SENT`，后者转为 FAILED，不自动重发。核对记录和原发送尝试分别保留。

模拟 worker 默认关闭。显式设置 `--news.publication.worker-enabled=true` 后每 5 秒处理最多 20 条到期操作，仍服从暂停和证据门禁。它没有真实发送能力。

## MySQL 与外部接入

MySQL 目标为独立 MySQL 8.4 schema 和用户；使用 `mysql` profile，并通过 `NEWS_DB_URL`、`NEWS_DB_USER`、`NEWS_DB_PASSWORD` 配置连接。不要使用 Trade 的库、账号或凭据。当前测试用 H2，MySQL 的实际迁移、锁及恢复语义仍需独立验证。

本构建只支持 `OFFLINE`；启用 `news.runtime.external-enabled` 或其他运行模式会启动失败。首选真实 AI 提供者为 OpenAI，通过 `EditorPort` 预留替换能力。真实凭据、协议、成本及质量验证留待后续批次，当前无需 API Key。

## 当前实现与边界

| 模块 | 当前能力 |
| --- | --- |
| `ingestion` | USGS、GDELT、Fed 离线解析；来源准入、URL 规范化及合成材料隔离 |
| `editorial` | 供应商中立的 `EditorPort`；结构化事实引用、冲突、时效与主题检查；合成英文编辑 |
| `publication` | 持久队列、租约、429 退避、UNKNOWN 核对、本地告警及可选模拟 worker |
| `growth` | 合成目标授权、幂等模拟分发、邀请键与已知/未知加入归因 |
| `automation` | 材料与事件版本、预算预占、运维接口和离线演示 |

单源发布已获确认，其他内容当前也不强制独立佐证；保留证据链数量的扩展能力。来源准入、证据充分性、冲突、风险与时效仍是门禁。GDELT 仅用于发现，Fed RSS 标题不足以支撑政策细节；真实来源均未批准和启用。

当前版本尚无真实采集与增量恢复、Fed 正文提取、跨媒体语义聚类、真实模型/Bot/推广协议及完整周期日报。自动修订的触发标准尚未确定，修订版本会保存并挂起，不自动选择编辑、更新或公开更正。正式重要性、时效、频率和预算参数不能沿用演示值。

项目使用独立配置，不访问 Trade 的服务、数据库、Bot 或凭据。真实来源许可、模型成本与质量、Telegram 权限和外部运营效果需要分别验证。

## 文档约定

项目内维护当前功能与使用说明。开发规划、过程日志、研究、验收清单和验证记录统一保存在桌面 `0/global-news-flash-archive`，按日期归档。
