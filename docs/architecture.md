# DDD 架构与模块职责

[返回首页](../README.md) · [配置](configuration.md) · [运维](operations.md)

项目采用模块化单体，以领域模型表达业务规则，通过 port 隔离用例与技术实现。模块按业务职责划分，按下面的依赖方向组织代码。

```text
interfaces / infrastructure 中的定时任务
                 ↓
              port/in
                 ↑ 实现
             application ──→ domain
                 ↓
              port/out
                 ↑ 实现
       infrastructure / integrations
```

## 各层边界

| 层 | 责任 | 依赖约束 |
| --- | --- | --- |
| `domain` | 领域对象、准入与证据校验、费用计算、状态转换权限、限流和重试决策 | 仅依赖 JDK、领域类型与 `shared.Hashing`；不访问数据库、HTTP、Spring 或 JSON 库 |
| `port/in` | 模块公开的用例契约及请求、结果类型 | 由 application 实现，供 Controller、定时任务和其他模块调用 |
| `port/out` | 仓储、来源采集、模型编辑、投递、快照编码与只读运行输入的契约 | 由外侧适配器实现；接口不暴露 Spring 配置类、MyBatis 或 Jackson 类型 |
| `application` | 加载状态、调用领域行为、组织跨模块用例、保存结果，安排事务、锁、租约和外部调用的顺序 | 依赖 domain 和 port；保留 Spring 的用例装配与事务支持，不直接依赖 Mapper、外部客户端或具体配置类 |
| `infrastructure` | SQL、数据库映射、配置绑定、序列化、资源加载、定时触发与依赖装配 | MyBatis Mapper 直接实现仓储 port，SQL 与映射注解只存在于适配器中 |
| `interfaces` | HTTP 参数校验、调用入站 port、映射响应 | 不调用具体 Service 或外部接入实现 |
| `integrations` | AI、新闻来源、Telegram、Webhook 的协议、鉴权、传输和解析 | 实现业务模块的出站 port；业务状态决策交给 domain |

JSON 编解码通过 `SnapshotCodec`，实现为 `JacksonSnapshotCodec`。原先嵌在 Mapper 中的预算、来源、任务与日报数据类型归入其所属领域；用例结果不再嵌在具体 Service 中。

## 模块职责

| 模块 | 领域责任 | 主要用例与出站契约 |
| --- | --- | --- |
| `ingestion` | 来源注册与准入、素材版本身份、待办状态和重试规则 | `IngestionUseCase`；`SourceRegistry`、`LiveIngestionRepository`、`SourceCatalog`、`SourceCollector`、`SourceAdapter` |
| `editorial` | 证据链、时效、题材与风险限制，引用、数字和稿件复验，模型费用估算 | `EditorialUseCase`；`EditorPort`、`ModelPricing`；规则在 `EditorialRules`、`ModelCost` |
| `publication` | 发布意图与修订约束、确认历史、渠道频率、投递结果和人工核对规则 | `PublicationUseCase`；`PublicationRepository`、`DeliveryGateway`、`PublicationGuard`；决策在 `PublicationPolicy`、`ChannelLimit` |
| `digest` | 已发布新闻的筛选、窗口、篇数与长度，日报快照和证据一致性 | `DigestUseCase`、`DigestEvidenceUseCase`、`DigestGuard`；`DigestRepository`；规则在各 Digest 领域策略中 |
| `automation` | 预算限额和预占状态、编辑任务状态、运营就绪条件、证据版本检查与告警重试 | 各自动化用例 port；预算、任务、事件版本与告警仓储；application 编排采集→编辑→发布→日报 |
| `growth` | 合成内容限制、目标授权、幂等键和邀请归因规则 | `GrowthUseCase`、`GrowthRepository`；规则在 `GrowthPolicy`，只用于离线演示 |

来源注册的存取契约由 ingestion 拥有，自动化与证据复验通过 `SourceRegistry` 读取。模块间复用已公开的用例或只读领域快照，不直接引用其他模块的 application 实现。

## 事务与副作用

- application 管理工作单元。模型调用前提交任务与预算预占，调用后在新的事务中结算；不把数据库锁跨越到真实模型请求。
- 发布先提交 SENDING 状态，再调用投递 port。超时或结果不明保留 UNKNOWN，不能把重构后的异常当作自动重发许可。
- 入队参与事件或日报的当前事务，保持快照与发布任务原子提交；原有锁顺序、CAS 与唯一键仍由仓储适配器执行。
- 采集通过 port 获得不可变快照和批次，application 将整批待办与分页进度一起提交。
- 领域策略接收已加载的状态和明确时间，返回决定或拒绝原因；不负责调用另一个 Service、发送消息或写表。

## 自动检查

在项目根目录执行：

```powershell
.\mvnw.cmd -B -ntp clean verify
```

`ArchitectureTest` 检查 domain 的依赖纯净性、application 对适配器的隔离、port 的独立性、入口对用例 port 的依赖，以及 application 之间不能直接引用具体实现。领域单元测试独立于 Spring 验证预算、发布限流、结果未知、日报筛选和采集重试；原有集成测试继续验证事务、去重、授权与恢复。

新增业务规则放入所属 domain，并添加领域测试；新增用例在 `port/in` 声明并由 application 编排；新增数据库或外部能力在 `port/out` 声明，由适配器实现。
