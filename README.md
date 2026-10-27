# Global News Flash

英文 Telegram 新闻后端，使用 Java 21、Spring Boot 4 和 MyBatis。支持离线演示，以及显式启用的真实采集、AI 编辑、Telegram 发布、日报与告警。

真实流程：**采集 → 来源与时效检查 → AI 编辑 → 引用校验 → 发布队列 → 发送前复验 → Telegram**。

当前仍处于联调阶段，完整真实发布链路和长期运行尚未验收。离线测试通过不代表新闻质量或线上运营已验证。

## 从哪里开始

- [配置与处理规则](docs/configuration.md)：外部服务清单、运行模式、环境变量、来源准入、预算和发布约束。
- [运维手册](docs/operations.md)：接口、Docker、备份与故障核对。
- [application.yml](src/main/resources/application.yml)：完整配置项及默认值；[.env.example](.env.example) 只列常用环境覆盖。

## 项目结构

```text
src/main/java/com/globalnewsflash/
  ingestion/     来源准入、采集状态、待办与素材持久化
  editorial/     编辑契约、证据校验和成稿规则
  publication/   发布队列、重试、人工核对与投递接口
  digest/        从已发布内容生成日报
  automation/    流程编排、预算、调度、运维与报表
  growth/        离线推广与邀请归因演示
  integrations/  外部服务接入
    ai/          OpenAI / DeepSeek、供应商选择、配置与响应解析
    sources/     Fed / USGS / GDELT / RSS、正文抓取与解析
    telegram/    Bot API、权限检查、消息格式与配置
    webhook/     告警 Webhook 投递与配置
    http/        有大小限制的 HTTP 响应读取
  shared/        通用哈希工具
src/main/resources/
  application.yml    通用配置与按 Profile 启用的配置分段
  db/migration/      数据库迁移历史
  fixtures/          离线演示和测试共用的合成数据
src/test/            单元与集成测试
scripts/             离线 smoke、MySQL 备份和恢复
docs/                当前配置与运维文档
```

业务模块内部按 `application`（用例与接入接口）、`domain`（规则与数据）、`infrastructure`（持久化、业务配置与运行装配）、`interfaces`（HTTP 入口）划分；只建立实际需要的层。外部 HTTP 调用实现集中在 `integrations`，AI、发布与告警分别实现业务侧的 `EditorPort`、`DeliveryGateway`、`NotificationGateway`；各业务模块继续管理准入、预算、队列和重试。数据库迁移保留完整版本链，已执行的迁移不要合并或重写。

## 构建与离线验证

需要 Java 21；Maven 由仓库 Wrapper 提供。Windows 使用 PowerShell 7：

```powershell
.\mvnw.cmd -B -ntp verify
.\scripts\smoke.ps1 -SkipBuild
```

smoke 检查重启去重、HTTP 鉴权和合成发布流程；结果默认写到系统临时目录，可通过 `-OutputDirectory <目录>` 指定位置。

单独运行合成演示：

```powershell
java -jar .\target\global-news-flash-0.1.0-SNAPSHOT.jar --spring.profiles.active=offline,demo --spring.main.web-application-type=none --news.demo.enabled=true --news.runtime.mode=OFFLINE --news.runtime.external-enabled=false
```

## 本地开发

```powershell
docker compose up -d
docker compose ps
java -jar .\target\global-news-flash-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql
```

MySQL 默认地址 `127.0.0.1:3307`，数据库与用户为 `global_news_flash`，本地开发密码为 `123456`。数据保存在 `A:/DockerData/global-news-flash/mysql`。应用默认监听 `127.0.0.1:8090`；健康检查为 `/actuator/health`，运维接口需要 `NEWS_OPS_TOKEN`。

要手动采集，改用 `mysql,live` Profile，然后调用 `POST /ops/ingestion/poll`。该 Profile 默认选择 DeepSeek 并启用已配置的 Fed 来源，自动任务和真实发送仍关闭；细节见[配置说明](docs/configuration.md#运行模式)。

## 功能边界

- 引用、数字和结构校验只能排除部分错误，不能证明语义正确；高风险内容会挂起。
- 跨媒体语义聚类与独立事实核实尚未实现；GDELT 仅用于发现。
- `growth` 仅为合成演示，阅读量、订阅变化和真实推广效果尚未接通。
- 项目保留当前代码、测试和用法；历史规划与联调记录保存在项目外的归档中。
