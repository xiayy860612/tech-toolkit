# 05 事件与 Event Bus

状态：设计基线（尚未落地）。本文按 [06-decisions.md](06-decisions.md) 的 D15、D20、D26、D27 同步。事件目录为提议版本，字段结构与消息队列选型标注“待定”的部分，实现时需先定下来。

## 1. 约定

- 跨服务的最终一致一律用事件，不做跨服务事务；平台 cell 与租户 cell 之间只通过事件交互，不做同步调用（D1、D27）。
- **Outbox**：发布方在业务事务内写 outbox 表，再由转发进程异步发布，保证业务数据与事件一致。
- **投递语义**：至少一次。消费者必须幂等，用事件 ID 去重。
- **顺序**：同一聚合（如同一个租户、同一个用户）的事件按顺序处理，需要保证顺序的事件用聚合 ID 作为分区键。
- **不放秘密**：事件里不放令牌、密码、密码哈希、IdP 密钥、初始密码等。
- **最小化个人信息**：事件只带消费者需要的字段。
- **实现**：Postgres Outbox 加轻量消息队列，不上 Kafka；具体选哪个消息队列为**待定**。

## 2. 事件信封

每个事件包含：

| 字段 | 说明 |
|---|---|
| `event_id` | 事件唯一标识，用于消费者去重 |
| `type` | 事件类型，格式 `<对象>.<动作>`，如 `tenant_user.created` |
| `version` | 事件结构的版本 |
| `occurred_at` | 发生时间 |
| `tenant_id` | 所属租户（平台级事件可为空） |
| `trace_id` | 链路追踪 ID |
| `payload` | 事件内容 |

字段命名与具体序列化格式为**待定**。

## 3. 事件目录（提议）

### 3.1 平台 cell 发布，租户 cell 与 UAM 订阅

| 事件 | 发布方 | 订阅方 | 用途 |
|---|---|---|---|
| `tenant.provisioning_requested` | Platform Service | 租户 UAM、Tenant Core | 开通租户（见 04） |
| `tenant.suspended` / `tenant.resumed` | Platform Service | 租户 UAM（拒绝签发与刷新，吊销该租户令牌）、Tenant Core（更新投影） | 暂停与恢复 |
| `entitlement.changed` | Platform Service | Tenant Core | 模块授权变更，更新只读投影 |

### 3.2 UAM 发布，Tenant Core 订阅

用户由 UAM 创建并生成 `tenant_user_id`（D20），Tenant Core 以该 ID 幂等建档案。前期 UAM 与 Tenant Core 合并部署（D29）时，3.2 与 3.3 的事件在进程内以 Spring events 传递并在同一事务内完成，事件契约不变，拆分后改经 Event Bus：

| 事件 | 发布方 | 订阅方 | 用途 |
|---|---|---|---|
| `tenant_user.created` | UAM | Tenant Core | 以 UAM 生成的 `tenant_user_id` 幂等创建档案，新用户没有角色；后期 JIT 创建的用户也发此事件 |
| `tenant_user.disabled` / `tenant_user.deleted` | UAM | Tenant Core | 同步用户状态；UAM 已先吊销该用户的全部令牌 |

事件里只带 `tenant_user_id`、`tenant_id` 和档案建立所需的最少信息（如登录名或邮箱），不带凭证。

### 3.3 Tenant Core 发布，UAM 订阅

| 事件 | 发布方 | 订阅方 | 用途 |
|---|---|---|---|
| `tenant_user.status_changed`（名称待定） | Tenant Core | UAM | 档案侧对用户的禁用，需要同步到 UAM 以吊销令牌；是否需要此事件取决于“禁用在哪里发起”，实现前先定下来 |

### 3.4 完成类事件

开通流程里各订阅方完成后发布“完成”事件，由 Platform Service 汇总（见 04）。事件名为**待定**。

### 3.5 暂不定义

- 租户 cell 向平台 cell 的事件（如用量指标）：平台级数据分析实现时再定义，守住“租户内部数据对平台侧不可见”。
- 业务模块自身的事件：随各业务模块设计定义，遵守本文的约定。
- 后期 broker、外部 IdP 接入引入的事件（如登录方式绑定变化）：接入时再定义。

## 4. 订阅方的行为

- 幂等：同一 `event_id` 重复到达只处理一次。
- 失败重试与死信：失败后按退避重试，超过次数进入死信队列并告警；具体策略为**待定**。
- 订阅方不得因为事件处理而同步回调发布方，需要回复时发布新事件。
- 事件消费者处理事件时，租户上下文来自事件里的 `tenant_id`，写入数据时同样要按 `tenant_id` 隔离。

## 5. 待定项

- 消息队列选型。
- 事件信封的字段命名与序列化格式。
- 重试、死信与告警策略。
- 开通流程里的完成类事件命名，以及“用户已创建”等事件的最终命名。
- 档案侧禁用用户是否需要同步回 UAM（3.3）。
