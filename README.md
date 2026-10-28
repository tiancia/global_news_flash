# Global News Flash

面向英文 Telegram 频道的新闻采集、编辑与发布后端，使用 Java 21、Spring Boot 4 和 MyBatis。

**采集 → 证据校验 → AI 编辑 → 发布队列 → 发送前复验 → Telegram**

[项目结构](#项目结构) · [模块与链路](docs/modules.md) · [本地启动](#本地启动) · [启动后检查](#启动后检查) · [文档导航](#文档导航)

## 项目概览

- **采集**：接入 Fed、USGS、RSS / Atom，管理来源准入、正文素材和待办；GDELT 用于发现线索。
- **编辑与发布**：支持 DeepSeek / OpenAI，提供证据校验、预算预占、发布队列、重试与人工核对。
- **运行控制**：提供运维接口、全局暂停、可选日报和告警，也支持离线合成演示。

当前处于联调阶段。应用启动和本地回归已验证，完整真实发布链路与长期运行尚未验收。

## 项目结构

```text
src/main/java/com/globalnewsflash/  业务模块、port 与外部接入
src/main/resources/
  application.yml                 公共配置及 Profile 差异
  db/migration/                   Flyway 数据库迁移
  fixtures/                       离线演示素材
src/test/                         领域、架构与集成测试
compose.yml                       基础服务入口，目前为 MySQL
deploy/docker/                    应用 Dockerfile 与可选容器编排
docs/                             配置、运维与架构文档
```

模块入口、调用链和数据所有权见[模块与链路导航](docs/modules.md)，DDD 分层见[架构与开发](docs/architecture.md)。各模块的 `package-info.java` 提供贴近代码的职责说明。

## 本地启动

以下以真实流程 `mysql,live` 为例，所有命令在项目根目录执行。
只做本地调试时，将 Profile 改为 `mysql`，默认不访问外部服务。

### 1. 准备环境

需要 Java 21、Docker Desktop 和 PowerShell 7；Maven 使用仓库自带的 Wrapper，无需单独安装。

真实流程需要在启动应用的进程或 IDE 中设置以下变量；只运行本地离线模式时，无需 AI 和 Telegram 密钥。

| 环境变量 | 用途 |
| --- | --- |
| `NEWS_OPS_TOKEN` | 独立运维令牌，至少 32 个字符 |
| `DEEPSEEK_API_KEY` | `live` 默认编辑供应商的密钥 |
| `NEWS_TELEGRAM_BOT_TOKEN` | 向目标频道发布消息的 Bot 令牌 |

频道默认值见 [application.yml](src/main/resources/application.yml)，可用 `NEWS_TELEGRAM_CHAT_ID` 覆盖。
`java -jar` 不会自动读取 `.env`；配置读取方式、供应商切换见[运行配置](docs/configuration.md)。

### 2. 启动 MySQL

```powershell
docker compose up -d
docker compose ps
```

确认 `db` 处于 `healthy` 状态。本机默认连接 `127.0.0.1:3307`，完整连接参数见[数据库配置](docs/configuration.md#数据库)。

### 3. 构建并启动应用

先构建运行包，以下命令跳过测试；开发验证命令见[开发检查](docs/architecture.md#开发检查)。

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.test.skip=true' package
```

启动真实流程：

```powershell
java -jar .\target\global-news-flash-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=mysql,live
```

也可以在 IDE 中直接运行 `GlobalNewsFlashApplication`，Active profiles 填写 `mysql,live`。
其他运行组合及默认开关见[运行模式](docs/configuration.md#运行模式)。

## 启动后检查

在已设置运维令牌的 PowerShell 中检查状态：

```powershell
$newsBase = 'http://127.0.0.1:8090'
$newsHeaders = @{ 'X-Ops-Token' = $env:NEWS_OPS_TOKEN }

Invoke-RestMethod "$newsBase/actuator/health"
Invoke-RestMethod "$newsBase/ops/status" -Headers $newsHeaders
Invoke-RestMethod "$newsBase/ops/automation" -Headers $newsHeaders
```

确认健康检查为 `UP`，运行模式为 `LIVE`、`externalEnabled=true`，自动流程的 `ready=true`。
若未就绪，先按返回的 `reasons` 处理；字段解释见[运行控制](docs/operations.md#运行控制)。

`live` 默认开启自动任务和真实发送，但全局暂停是独立状态：初始为 `true`，重启保留上次值。
两个开关都开启时，上次已解除暂停的流程会在重启后继续执行，上次暂停的流程则继续暂停。
确认配置与目标频道后，需要开始执行时解除暂停：

```powershell
Invoke-RestMethod "$newsBase/ops/pause" -Method Post `
  -Headers $newsHeaders -ContentType 'application/json' -Body '{"paused":false}'
```

自动流程随后按调度执行。可在 `/ops/automation` 查看周期记录，在 `/ops/publications` 查看投递结果，并核对频道实际消息；启动或 `ready=true` 本身不代表整条真实链路已经跑通。
日常暂停、手动触发和异常核对见[日常运维](docs/operations.md)。

## 文档导航

| 想了解什么 | 去哪里看 |
| --- | --- |
| 每个模块负责什么、从哪个类读起、链路如何串联 | [模块与链路导航](docs/modules.md) |
| 运行模式、环境变量、来源与处理规则 | [运行配置](docs/configuration.md) |
| 暂停与恢复、接口、Docker 和故障处理 | [日常运维](docs/operations.md) |
| 模块职责、DDD 分层和开发约定 | [架构与开发](docs/architecture.md) |

完整参数维护在 [application.yml](src/main/resources/application.yml)，常用覆盖项见 [.env.example](.env.example)。

## 功能边界

- 引用和数字校验不能保证语义正确；高风险或证据不足的内容会挂起。
- 跨媒体语义聚类和独立事实核实尚未实现；GDELT 仅用于发现线索。
- `growth` 仅用于合成演示，未接通真实推广、阅读量和订阅归因。
