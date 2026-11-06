# 系统架构设计（system-design）

本目录沉淀 SaaS 微服务平台的架构设计，是各组件实现的根本依据。

> **当前状态：设计基线，尚未落地。** 本目录内容来自 `specs/saas-platform-architecture/research.md` 的调研结论。实现开始后，随实现演进更新，使之保持“当前真实状态”（见仓库根目录 `AGENTS.md` 对 `docs/system-design/` 的定义）。

## 如何使用

- 实现任一组件前，先读 [01-overview.md](01-overview.md) 中该组件的职责行，再读相关主题文档；组件的边界、数据归属、依赖方向以这里为准。
- 设计或需求发生变更时，按根目录 `AGENTS.md` 的规则：先更新 `specs/<功能>/` 下的 `spec.md`、`plan.md`、`tasks.md`，经确认后再改实现；实现落地后回写本目录。
- 每份文档的末尾或 [06-decisions.md](06-decisions.md) 记录待定项。待定项定下来之后，把结论写进正文，并从待定项里删除。
- 文档里的状态标记：**已定**表示可作为实现依据；**待定**表示尚未决定，实现时不得自行假设。

## 目录

| 文档 | 内容 |
|---|---|
| [01-overview.md](01-overview.md) | 背景与约束、术语、总览图、分层、组件职责表、前期取舍 |
| [02-authentication.md](02-authentication.md) | UAM（自研 Auth 服务）、不透明令牌与内部 JWT 契约、网关换发、多端接入、登录方式扩展、用户与会话归属、密钥与 JWKS |
| [03-service-layering-and-trust.md](03-service-layering-and-trust.md) | 服务分层（平台级与业务级）、调用规则、服务间信任与来源识别 |
| [04-multi-tenancy.md](04-multi-tenancy.md) | 租户注册表、平台租户、pool / silo、cell 与入口路由、租户开通与暂停、模块授权 |
| [05-events.md](05-events.md) | Event Bus 与 Outbox 约定、事件目录 |
| [06-decisions.md](06-decisions.md) | 关键决策记录、前期省掉的功能与触发条件、待定项 |
| `assets/` | 架构图（draw.io 的 `.drawio.svg`，可直接用 draw.io 打开编辑，也可在 Markdown 中渲染） |

## 一页摘要

- **按 cell 部署**：平台是特殊的“平台租户”，独立 cell；租户 cell 可被多个租户共用（pool）或单租户独占（silo）。每个 cell 自带网关、UAM、BFF、Tenant Core 与审计，cell 之间互不同步调用，只通过 Event Bus 交互；租户用户与租户内部数据对平台侧不可见。
- **分层**：入口层、业务服务层（平台级服务与业务级服务并列）、核心域层、基础服务层；同步调用只向下，同层、向上、跨 cell 一律改用事件；UAM 作为侧栏。
- **认证**：自研 UAM，第一阶段用户名密码，登录方式通过 `Authenticator` 接口扩展，一个用户可关联多种登录方式。终端只持有不透明令牌，网关 ext_authz 校验后换成短有效期的内部 JWT，各服务只做本地验签。
- **契约**：`tenant_id` 来自 UAM 的认证配置；用户的角色、租户状态、已开通模块由 Tenant Core 提供，不进 token；下游只用 `tenant_user_id`。
- **前期合并**：UAM 与 Tenant Core 合并部署为一个服务，模块边界保留。
- **前期不做**：broker 与外部 IdP、外部服务 M2M、JIT、微信、Service Mesh、通用限流、API Key、on-behalf-of。触发条件见 [06-decisions.md](06-decisions.md)。

## 图

| 图 | 说明 |
|---|---|
| [01-architecture-overview](assets/01-architecture-overview.drawio.svg) | 目标架构总览（平台 cell 与租户 cell） |
| [02-gateway-token-exchange](assets/02-gateway-token-exchange.drawio.svg) | 网关 ext_authz 校验不透明令牌并换发内部 JWT |
| [03-uam-identity-model](assets/03-uam-identity-model.drawio.svg) | UAM 的身份与令牌数据模型 |
| [04-service-layering](assets/04-service-layering.drawio.svg) | 服务分层与调用方向 |
| [05-service-trust](assets/05-service-trust.drawio.svg) | 服务间的三道校验、信任根与两种调用模式 |
| [06-auth-service-modules](assets/06-auth-service-modules.drawio.svg) | UAM 认证模块的内部划分 |
| [07-auth-service-interaction](assets/07-auth-service-interaction.drawio.svg) | 登录与调用的交互时序 |

![目标架构总览](assets/01-architecture-overview.drawio.svg)
