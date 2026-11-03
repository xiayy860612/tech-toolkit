# 01 架构总览

状态：设计基线（尚未落地）。

## 1. 背景与目标

搭建一个小型的微服务 SaaS 平台：不同租户启用不同的业务模块，业务服务独立部署，平台侧与租户侧在数据和权限上严格隔离。

**约束**（已定）：

- 技术栈：后端 Java / Spring Boot，SSR 前端 Next.js，部署在 Kubernetes，网关使用 Envoy Gateway。
- 接入端：Web（SSR，非 SPA）、桌面端、外部服务。
- 身份：前期用 Keycloak 作为 Auth 服务，登录使用 Keycloak 内置用户；支持租户自带 OIDC IdP（每租户可配置多个），微信放在后期。
- 边界：租户用户和租户内部数据对平台侧不可见；平台侧与租户侧分库、分审计。
- 所有服务（包括 BFF）必须支持水平扩展。
- 前期规模：租户不超过 5 个，因此前期尽量简化，只保留边界，不提前做重的基础设施。

**设计目标**：

1. 每一跳都校验，不因为在内网就信任。
2. token 由 Auth 统一签发，边界与服务只做本地验签，不换发。
3. 先把边界定对；早期可以合并部署，后期再拆分。

## 2. 核心术语

| 术语 | 含义 |
|---|---|
| Tenant | 租户，使用平台的客户组织 |
| tenant_user | 租户内的用户，档案归属租户侧的 Tenant Core |
| platform_staff | 平台运维人员，档案归属平台侧的 Platform Service |
| 身份域（domain） | 分 staff 与 tenant 两个域，使用不同的 `iss`、`aud` 和签名密钥；token 里的 claim 为 `domain` |
| Keycloak realm | Keycloak 内的隔离单元。前期每个租户一个 realm，staff 单独一个 realm；与身份域不是同一个概念 |
| 平台侧 / 租户侧 | 服务的归属。租户侧服务可按 pool（共享）或 silo（独占）部署，通过 `cell_id` 路由 |
| Auth 服务 | 负责登录、对接外部 IdP、签发 token。前期是 Keycloak 集群加自研的 Auth Admin 门面 |
| Auth Admin 门面 | 自研的薄服务，租户管理员经它管理 IdP、client、用户，由它调用 Keycloak Admin API |
| token | 内部服务认可的 token 规范（有效期 5 到 10 分钟），由 Keycloak 直接签发给 user 与 M2M client，契约见 02 |
| BFF | Backend for Frontend，SSR 应用的服务端，持有用户 session |
| Outbox | 在业务事务内写 outbox 表，再异步发布事件，保证业务数据与事件一致 |

## 3. 总览图

![目标架构总览](assets/01-architecture-overview.drawio.svg)

阅读要点：

- 横向四个带是服务分层，同步调用只向下（见 03）。
- 认证服务是侧栏：入口层向它登录、取 token，各层向它取 JWKS 验签。
- Web 经各自的 BFF 访问；桌面端和外部服务直接进入 Envoy Gateway。
- Tenant BFF 通过 Envoy Gateway 调用租户侧；Platform BFF 直接调用 Platform Service（内网）。
- 平台侧创建租户、暂停租户等操作通过 Outbox 发布事件，租户侧订阅，平台侧不直接调租户侧。
- 平台侧和租户侧各有独立的审计存储。
- 外部 IdP 只与 Keycloak 对接，下游服务只依赖 Keycloak 的 JWKS，不关心外部 IdP 是谁。

## 4. 分层

| 层 | 组件 | 说明 |
|---|---|---|
| 入口层 | Platform BFF、Tenant BFF、Envoy Gateway | 面向外部的入口，凭证校验与路由 |
| 业务服务层 | Business Services | 按模块独立部署、按租户启用，仅租户侧 |
| 核心域层 | Platform Service、Tenant Core | 平台与租户的核心领域数据与规则 |
| 基础服务层 | Audit（平台侧与租户侧各一）、Notification | 只提供能力，不依赖任何上层服务 |
| 侧栏 | Keycloak、Auth Admin 门面 | 认证服务，不属于任何一层 |

调用规则见 [03-service-layering-and-trust.md](03-service-layering-and-trust.md)。

## 5. 组件职责表

实现某个组件前，以本表为边界依据。“不负责”一栏同样是约束。

### 5.1 入口层

| 组件 | 负责 | 不负责 | 主要依赖 |
|---|---|---|---|
| Envoy Gateway | TLS 终止、路由、JWT 验签（多个 provider，每个 realm 一个 `iss`，远程 JWKS）、检查 `iss` 白名单、`aud`、`exp`，通过后转发同一个 token | 业务授权、限流（前期不做）、换发 token、租户状态检查 | Keycloak JWKS；租户注册表生成的 `iss` 清单（配置） |
| Tenant BFF | 租户管理端的 SSR 与聚合；持有用户 session（HttpOnly cookie，session 存 Redis）；作为 Keycloak 的第一方 client 走授权码流程；通过 Gateway 调用租户侧；租户管理 IdP、M2M client 时调用 Auth Admin 门面 | 校验业务权限、保存 token 到浏览器 | Keycloak、Envoy Gateway、Auth Admin 门面、Redis |
| Platform BFF | 平台管理端的 SSR 与聚合；持有 staff session；作为 staff realm 的第一方 client；直接调用 Platform Service | 访问租户侧服务 | Keycloak（staff realm）、Platform Service、Redis |

### 5.2 核心域层

| 组件 | 负责 | 不负责 | 主要依赖 |
|---|---|---|---|
| Platform Service | 租户注册表（tenant registry，含 `auth_issuer`、部署形态、`cell_id`）、模块授权（entitlement）、platform_staff 档案与角色、租户开通 / 暂停的发起；通过 Outbox 发布事件；自己验 staff token | 租户用户与租户内部数据、租户业务数据 | 自有数据库、Event Bus |
| Tenant Core | tenant_user 档案、成员关系、角色与 RBAC、租户配置；提供权限查询接口（返回角色、租户状态、已开通模块）；发布用户事件；订阅平台侧的租户与模块授权事件，维护只读投影 | 登录凭证（归 Keycloak）、租户注册（归 Platform Service） | 自有数据库、Event Bus |

### 5.3 业务服务层

| 组件 | 负责 | 不负责 | 主要依赖 |
|---|---|---|---|
| Business Services | 各业务模块的领域逻辑；本地验签；按 `tenant_id` 隔离数据；向 Tenant Core 查询角色并判断模块是否开通（短 TTL 缓存） | 登录、签发 token、跨租户访问 | Keycloak JWKS、Tenant Core、各自数据库 |

### 5.4 基础服务层

| 组件 | 负责 | 不负责 |
|---|---|---|
| Audit（Platform Audit / Tenant Audit） | 各自侧的审计日志存储与查询；记录最初发起者与 trace id | 跨侧查询：平台侧看不到租户侧审计 |
| Notification | 通知发送（邮件、站内信等） | 业务决策 |

### 5.5 认证侧栏

| 组件 | 负责 | 不负责 | 主要依赖 |
|---|---|---|---|
| Keycloak 集群 | OIDC Provider；内置用户登录；对接租户自带的 OIDC IdP；签发 token；提供 JWKS；管理会话与 refresh token；Back-Channel Logout；每租户一个 realm、staff 单独一个 realm | 业务授权、用户档案与 RBAC、租户注册 | 自有数据库 |
| Auth Admin 门面 | 租户 IdP 配置管理（含 SSRF 校验、锁死保护）；M2M client 管理；订阅 Tenant Core 用户事件，在 Keycloak 创建、禁用用户；开通租户时创建 realm 及 client、mapper；审计配置变更；是租户管理员访问 Keycloak 的唯一入口 | 登录流程本身、签发 token | Keycloak Admin API、Event Bus |

### 5.6 共享基础设施

| 组件 | 说明 |
|---|---|
| Event Bus | Postgres Outbox 加轻量消息队列，不上 Kafka（见 05） |
| NetworkPolicy | 默认拒绝，服务间最小授权 |
| Secrets / KMS | 密钥与配置 |
| Observability | 日志、指标、trace（OpenTelemetry） |

## 6. 前期取舍

前期规模小，以下功能明确不做，保持边界不变、需要时再加（触发条件见 [06-decisions.md](06-decisions.md)）：

- 不自建 Edge Auth，由 Envoy Gateway 的 JWT 验证承担验签。
- 不上 Service Mesh，用 NetworkPolicy 默认拒绝加每个服务验 token。
- 不做任何限流，作为后续功能项。
- 不做 JIT（首次登录自动创建用户），用户预置。
- 不做 API Key，外部服务用 client_credentials。
- 不做 on-behalf-of（token exchange），服务间只有“透传”和“service token”。
- 微信登录放在后期。
- 平台侧职责（tenant registry、staff 管理、模块授权）可先放在一个 Platform Service 里，内部用 Spring events 分模块，边界稳定后再拆。
