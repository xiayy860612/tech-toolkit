# 01 架构总览

状态：设计基线（尚未落地）。本文按 [06-decisions.md](06-decisions.md) 的 D16 到 D26 同步，认证细节见 [02-authentication.md](02-authentication.md)。

## 1. 背景与目标

搭建一个小型的微服务 SaaS 平台：不同租户启用不同的业务模块，业务服务独立部署，平台侧与租户侧在数据和权限上严格隔离。

**约束**（已定）：

- 技术栈：后端 Java / Spring Boot，SSR 前端 Next.js，部署在 Kubernetes，网关使用 Envoy Gateway。
- 接入端：Web（SSR，非 SPA）、桌面端；外部服务（M2M）后期接入。
- 身份：自研 UAM 作为 Auth 服务，第一阶段登录使用用户名密码；后期支持租户自带 OIDC IdP、broker（如 Keycloak）、微信，且多种登录方式可关联到同一个用户。
- 边界：租户用户和租户内部数据对平台侧不可见；平台侧是一个特殊的“平台租户”，独立 cell 部署，与租户 cell 分库、分审计。
- 所有服务（包括 BFF）必须支持水平扩展。
- 前期规模：租户不超过 5 个，因此前期尽量简化，只保留边界，不提前做重的基础设施。

**设计目标**：

1. 每一跳都校验，不因为在内网就信任。
2. 终端只持有不透明令牌，网关统一换成短有效期的内部 JWT，各服务只做本地验签。
3. 先把边界定对；早期可以合并部署，后期再拆分。

## 2. 核心术语

| 术语 | 含义 |
|---|---|
| Tenant | 租户，使用平台的客户组织 |
| 平台租户 | 特殊的保留租户，承载平台自己的模块（Platform Service、未来的数据分析等），独立 cell 部署 |
| 平台级服务 | 平台租户的模块（Platform Service、未来的数据分析等），只部署在平台 cell，服务平台 staff |
| 业务级服务 | 租户的业务模块，部署在租户 cell，按租户启用，服务租户用户 |
| cell | 一套独立部署单元（网关、服务、库、UAM、审计），不与其他 cell 共用。一个 cell 可以被多个租户使用（pool cell），silo cell 只服务一个租户。租户 cell 可按 pool（共享）或 silo（独占）部署，通过 `cell_id` 路由；平台租户占独立的平台 cell |
| tenant_user | 租户内的用户，档案归属所在 cell 的 Tenant Core；platform_staff 就是平台租户的 tenant_user |
| UAM | User Account Management，自研的 Auth 服务，负责登录、身份、令牌签发。每个 cell 各有一套部署，信任根互相独立 |
| identity | 用户的一种登录身份（密码、OIDC、微信等），一个用户可有多条 |
| Authenticator | UAM 里承载登录方式的扩展接口，输出统一的“已验证身份” |
| 不透明令牌 | UAM 签发给终端的令牌，不含信息，服务端查表得到身份 |
| 内部 JWT | 网关 ext_authz 把不透明令牌换成的短有效期 JWT，不出集群，契约见 02 |
| ext_authz | 网关上的外部授权组件，负责校验不透明令牌并换发内部 JWT |
| broker | 后期可接入的外部登录提供方（如 Keycloak），第一阶段不引入 |
| BFF | Backend for Frontend，SSR 应用的服务端，持有用户 session |
| Outbox | 在业务事务内写 outbox 表，再异步发布事件，保证业务数据与事件一致 |

## 3. 总览图

![目标架构总览](assets/01-architecture-overview.drawio.svg)

阅读要点：

- 横向四个带是服务分层，同步调用只向下（见 03）。UAM 是侧栏，不属于任何一层。
- 每个 cell 有自己的网关（Envoy Gateway + ext_authz）、UAM、Tenant Core 与审计，不共用网关（D28）；进入 cell 之前的入口路由在 cell 之外解决，倾向每租户一个域名，待定。
- Web 经各自的 BFF 访问，桌面端直接进入网关；BFF 与桌面端都只持有不透明令牌，BFF 不接触内部 JWT。
- 平台侧是一个特殊的租户，部署在独立的平台 cell：自己的 UAM、Tenant Core、库与审计。Platform BFF 经网关的内部 listener 访问（D25）。
- 平台 cell 创建租户、暂停租户等操作通过 Outbox 发布事件，租户 cell 订阅，不做跨 cell 的同步调用。
- 后期的外部 IdP、broker、微信只与 UAM 对接，下游服务只依赖内部 JWT 的契约，不关心用户是怎么登录的。

## 4. 分层

| 层 | 组件 | 说明 |
|---|---|---|
| 入口层 | Platform BFF、Tenant BFF、Envoy Gateway（含 ext_authz） | 面向外部的入口，凭证校验、换发与路由 |
| 业务服务层 | 平台级服务（Platform Service、未来的数据分析）、业务级服务（Business Services） | 两类并列，分属平台 cell 与租户 cell，互不同步调用（D27）；业务服务按租户启用 |
| 核心域层 | Tenant Core | 用户档案、角色与 RBAC、租户配置，每个 cell 一套 |
| 基础服务层 | Audit、Notification | 只提供能力，不依赖任何上层服务；每个 cell 一套 Audit |
| 侧栏 | UAM | 认证服务，不属于任何一层；平台与租户各一套部署 |

UAM（侧栏）与 Tenant Core（核心域层）前期合并部署为一个服务，按模块保留边界（D29）；下文组件表仍按逻辑组件分列，边界不变。

调用规则见 [03-service-layering-and-trust.md](03-service-layering-and-trust.md)。

## 5. 组件职责表

实现某个组件前，以本表为边界依据。“不负责”一栏同样是约束。

### 5.1 入口层

| 组件 | 负责 | 不负责 | 主要依赖 |
|---|---|---|---|
| Envoy Gateway + ext_authz（每个 cell 一套） | TLS 终止、按路径路由到本 cell 的服务；ext_authz 校验不透明令牌（未过期、未吊销，用户与租户启用），换成内部 JWT 转发；失败即拒绝；JWT 按令牌缓存 | 业务授权、通用限流（前期不做）、签发令牌 | UAM 的令牌数据（Redis 加本地缓存）、签名密钥（KMS 加密） |
| Tenant BFF | 租户管理端的 SSR 与聚合；持有用户 session（HttpOnly cookie，session 存 Redis，里面是不透明令牌）；经 UAM 登录；通过网关调用租户侧；租户管理员管理用户、登录方式时调用 UAM 的管理接口 | 校验业务权限、保存令牌到浏览器、接触内部 JWT | UAM、Envoy Gateway、Redis |
| Platform BFF | 平台管理端的 SSR 与聚合；与 Tenant BFF 同一份代码，部署在平台 cell；持有 staff session；经平台 UAM 登录；经平台 cell 自己的网关（内部 listener）调用平台服务 | 访问租户 cell 的服务、接触内部 JWT | 平台 UAM、Envoy Gateway、Redis |

### 5.2 核心域层

| 组件 | 负责 | 不负责 | 主要依赖 |
|---|---|---|---|
| Tenant Core | tenant_user 档案（含平台 cell 里的 platform_staff）、成员关系、角色与 RBAC、租户配置；提供权限查询接口（返回角色、租户状态、已开通模块）；订阅 UAM 的用户事件，以 UAM 生成的 `tenant_user_id` 幂等建档案（新用户无角色）；订阅平台 cell 的租户与模块授权事件，维护只读投影 | 登录凭证与身份绑定（归 UAM）、租户注册（归 Platform Service） | 自有数据库、Event Bus |

### 5.3 业务服务层：平台级与业务级

两类服务都在业务服务层，但分属不同 cell、不同信任根（D27）：

| | 平台级服务 | 业务级服务 |
|---|---|---|
| 例子 | Platform Service、未来的数据分析 | 租户的各业务模块 |
| 部署 | 只在平台 cell | 租户 cell（pool 或 silo） |
| 服务对象 | 平台 staff | 租户用户 |
| 信任的 `iss` | 平台 UAM | 租户 UAM |
| 数据 | 租户注册表、模块授权等平台数据 | 租户业务数据，按 `tenant_id` 隔离 |

两类服务之间不做同步调用，跨 cell 只用事件；业务级服务不依赖平台级服务，租户注册与授权经事件投影到本 cell 的 Tenant Core，由 Tenant Core 提供查询。

| 组件 | 负责 | 不负责 | 主要依赖 |
|---|---|---|---|
| Platform Service（平台级） | 平台租户的模块，运行在平台 cell。租户注册表（含部署形态、`cell_id`、UAM 部署指向）、模块授权（entitlement）、租户开通与暂停的发起；通过 Outbox 发布事件；验平台 UAM 的内部 JWT | 租户用户与租户内部数据、租户业务数据 | 自有数据库、Event Bus、Tenant Core（平台 cell） |
| 其他平台级服务（如数据分析） | 后期，按平台租户的模块授权启用；数据来源与边界实现时再定（见 06） | 读取租户业务库 | 待定 |
| Business Services（业务级） | 各业务模块的领域逻辑；本地验签内部 JWT；按 `tenant_id` 隔离数据；向 Tenant Core 查询角色并判断模块是否开通（短 TTL 缓存） | 登录、签发令牌、跨租户访问 | UAM 的 JWKS、Tenant Core、各自数据库 |

### 5.4 基础服务层

| 组件 | 负责 | 不负责 |
|---|---|---|
| Audit | 各 cell 自己的审计日志存储与查询；记录最初发起者与 trace id；平台 cell 与租户 cell 是同一服务的不同部署 | 跨 cell 查询：平台侧看不到租户侧审计 |
| Notification | 通知发送（邮件、站内信等），含找回密码 | 业务决策 |

### 5.5 认证侧栏

| 组件 | 负责 | 不负责 | 主要依赖 |
|---|---|---|---|
| UAM | 登录（第一阶段为用户名密码，密码用 argon2id，失败锁定与登录端点限流）；用户、identity、credential 管理；签发、刷新、吊销不透明令牌；提供内部 JWT 的 JWKS；生成 `tenant_user_id` 并发用户事件；管理接口（用户、登录方式、令牌吊销，后期含 IdP 配置与 M2M）；响应租户开通事件；CLI 冷启动；配置变更审计 | 业务授权、用户档案与 RBAC、租户注册 | 自有数据库、Event Bus、Notification |

### 5.6 共享基础设施

| 组件 | 说明 |
|---|---|
| Event Bus | Postgres Outbox 加轻量消息队列，不上 Kafka（见 05） |
| Redis | BFF session、令牌查询缓存 |
| NetworkPolicy | 默认拒绝，服务间最小授权 |
| Secrets / KMS | 密钥与配置，内部 JWT 的签名密钥由 KMS 加密保存 |
| Observability | 日志、指标、trace（OpenTelemetry） |

## 6. 前期取舍

前期规模小，以下功能明确不做，保持边界不变、需要时再加（触发条件见 [06-decisions.md](06-decisions.md)）：

- 不上 Service Mesh，用 NetworkPolicy 默认拒绝加每个服务验 token。
- 不做通用限流；UAM 登录端点的失败锁定与限流是例外，第一阶段就做。
- 不引入 broker、外部 IdP、JIT、微信登录，登录方式靠 Authenticator 接口预留。
- 不做外部服务（M2M）接入，后期随 broker 接入。
- 不做 API Key。
- 不做 on-behalf-of（令牌交换），服务间只有“透传”和“service token”。
- 平台级数据分析服务后期再做，数据边界届时再定。
- UAM 与 Tenant Core 前期合并部署为一个服务（D29），内部按模块分开，接口与事件契约不变，需要时再拆。
- Platform Service 内部（租户注册、模块授权等）可先放在一起，用 Spring events 分模块，边界稳定后再拆。
