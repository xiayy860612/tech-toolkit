# 05 事件与 Event Bus

状态：设计基线（尚未落地）。事件目录为提议版本，字段结构与消息队列选型标注“待定”的部分，实现时需先定下来。

## 1. 约定

- 跨服务的最终一致一律用事件，不做跨服务事务；平台侧与租户侧之间只通过事件交互。
- **Outbox**：发布方在业务事务内写 outbox 表，再由转发进程异步发布，保证业务数据与事件一致。
- **投递语义**：至少一次。消费者必须幂等，用事件 ID 去重。
- **顺序**：同一聚合（如同一个租户、同一个用户）的事件按顺序处理，需要保证顺序的事件用聚合 ID 作为分区键。
- **不放秘密**：事件里不放 client secret、IdP 密钥、密码等。
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

### 3.1 平台侧发布，租户侧与认证侧订阅

| 事件 | 发布方 | 订阅方 | 用途 |
|---|---|---|---|
| `tenant.provisioning_requested` | Platform Service | Tenant Core、Auth Admin 门面 | 开通租户（见 04） |
| `tenant.suspended` / `tenant.resumed` | Platform Service | Tenant Core（更新投影）、Auth Admin 门面（是否禁用 realm 待定） | 暂停与恢复 |
| `entitlement.changed` | Platform Service | Tenant Core | 模块授权变更，更新只读投影 |

### 3.2 租户侧发布，认证侧订阅

| 事件 | 发布方 | 订阅方 | 用途 |
|---|---|---|---|
| `tenant_user.created` | Tenant Core | Auth Admin 门面 | 在租户 realm 创建 Keycloak 用户，写入 `tenant_user_id` |
| `tenant_user.disabled` / `tenant_user.deleted` | Tenant Core | Auth Admin 门面 | 禁用或删除 Keycloak 用户并吊销其会话 |

### 3.3 完成类事件

开通流程里各订阅方完成后发布“完成”事件，由 Platform Service 汇总（见 04）。事件名为**待定**。

### 3.4 暂不定义

- 租户侧向平台侧的事件（如用量计量）：计量功能后期再做时定义。
- 业务模块自身的事件：随各业务模块设计定义，遵守本文的约定。

## 4. 订阅方的行为

- 幂等：同一 `event_id` 重复到达只处理一次。
- 失败重试与死信：失败后按退避重试，超过次数进入死信队列并告警；具体策略为**待定**。
- 订阅方不得因为事件处理而同步回调发布方，需要回复时发布新事件。

## 5. 待定项

- 消息队列选型。
- 事件信封的字段命名与序列化格式。
- 重试、死信与告警策略。
- 开通流程里的完成类事件命名。
