# 系统架构设计（system-design）

本目录沉淀 SaaS 微服务平台的架构设计，是各组件实现的根本依据。

> **当前状态：设计基线，尚未落地。** 实现开始后，随实现演进更新，使之保持“当前真实状态”（见仓库根目录 `AGENTS.md` 对 `docs/system-design/` 的定义）。

## 如何使用

- 实现任一组件前，先读 [01](01-overview.md) 中该组件的职责行，再读相关主题文档；组件的边界、数据归属、依赖方向以这里为准。
- 设计或需求发生变更时，按根目录 `AGENTS.md` 的规则：先更新 `specs/<功能>/` 下的 `spec.md`、`plan.md`、`tasks.md`，经确认后再改实现；实现落地后回写本目录。
- **待定项只在 [06](06-decisions.md) 集中记录**，正文用“待定”标注；定下来后把结论写进正文，并从 06 删除。“待定”表示尚未决定，实现时不得自行假设。

## 目录

| 文档 | 内容 |
|---|---|
| [01-overview.md](01-overview.md) | 背景与约束、术语、总览图、组件职责表、前期取舍 |
| [02-authentication.md](02-authentication.md) | UAM、不透明令牌与内部 JWT、网关换发、多端接入、登录方式扩展、密钥 |
| [03-service-layering-and-trust.md](03-service-layering-and-trust.md) | 服务分层（平台级与业务级）、调用规则、服务间信任 |
| [04-multi-tenancy.md](04-multi-tenancy.md) | 租户注册表、平台租户、pool / silo、入口路由、开通与暂停、模块授权 |
| [05-events.md](05-events.md) | Event Bus 与 Outbox 约定、事件目录 |
| [06-decisions.md](06-decisions.md) | 关键决策、前期省掉的功能与触发条件、待定项 |
| `assets/` | 架构图（draw.io 的 `.drawio.svg`，可用 draw.io 打开编辑，也可在 Markdown 中渲染）；各图嵌在对应文档里 |

## 一页摘要

- **按 cell 部署**：平台是特殊的“平台租户”，独立 cell；租户 cell 可多租户共用（pool）或单租户独占（silo）。每个 cell 自带网关、BFF、Tenant Core（含 UAM）与审计，cell 之间只通过 Event Bus 交互；租户用户与内部数据对平台侧不可见。
- **认证**：自研 UAM，第一阶段用户名密码，登录方式可扩展、多种方式可关联同一用户。终端只持有不透明令牌，网关 ext_authz 换成短有效期的内部 JWT，各服务本地验签。
- **前期不做**：broker 与外部 IdP、外部服务 M2M、JIT、微信、Service Mesh、通用限流。触发条件见 06。

![目标架构总览](assets/01-architecture-overview.drawio.svg)
