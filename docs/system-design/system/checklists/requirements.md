# Specification Quality Checklist: Admin Center 用户管理与会话管理（User Manager / Session Manager）

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-23
**Feature**: [spec.md](../../../achieve-specs/admin-center/spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Validation iteration 1 of 3: all items pass; no spec changes were required.
- **2026-10-23 需求变更后复核**：用户追加了强制下线会话、停用用户、创建「账号名 / 密码」用户，并确认账号与会话一对一。spec 已据此新增 User Story 4–6、FR-015 / FR-021–FR-025 / FR-028 / FR-031、SC-004–SC-006，其余条目重新编号；重新逐项核对后全部通过，仍无 [NEEDS CLARIFICATION]。
- **Mandated terms, not implementation leaks**: "cookie 中的 session id" 与「已签名、可验证的会话数据（参考图中为 JWT）」直接来自用户输入与参考图二，作为行为约束保留；规格不指定语言、框架、存储、签名算法或有效期，这些留给 plan。
- **设计决策已由用户确认**：规格基线为 `main`（不依赖未合并的 `001-platform-web-uam`），目录编号取 `002` 以避开 001。
- **按默认假设处理（未标 NEEDS CLARIFICATION）**：不引入权限与角色（所有已登录用户均视为运维人员，现在也包括对任何用户的停用与强制下线，风险已写入 Assumptions）；提供「启用」以配套「停用」（用户只要求了停用）；新建用户的密码强度、账号名规则取默认值；本期无审计。账号与会话一对一已由用户确认。如需调整请在 `/speckit-clarify` 中提出。
- **2026-10-23 架构澄清**：Admin Center 是接入层 Web Server（登录后基于会话访问），UAM 是 RESTful 领域服务（访问不基于会话，每次调用自带会话数据）。spec 已同步到概述、交互流程、Clarifications、FR-008、FR-013、Key Entities 与 Assumptions，条目未增删；仍无 [NEEDS CLARIFICATION]。**留给 plan 的关键问题**：UAM 一侧对「已结束会话的会话数据」的最长接受窗口及其对强制下线 / 停用时效的影响（见 Assumptions）。
- **2026-10-23 clarify 第 1、2 问**：确认本期不分角色（任何已登录用户可执行全部写操作）；确认已登录用户可修改自己的密码（需当前密码），运维人员重置他人密码与忘记密码找回不做。spec 新增 User Story 7、FR-026 / FR-027、SC-007，其后条目顺延重排（现为 11 个用户故事、40 条功能需求、15 条成功标准）；仍无 [NEEDS CLARIFICATION]。
- **2026-10-23 clarify 第 3 问**：确认写操作只写应用日志（操作人、对象、动作、时间、结果；不含密码与会话标识；无界面）。spec 新增 FR-034，其后 FR 顺延（现为 41 条功能需求），并在 SC-014 增加日志校验；仍无 [NEEDS CLARIFICATION]。clarify 三问全部完成，16 项检查全部保持通过，无回退。
- **2026-10-23 clarify**：确认本期不做客户端凭据（服务间凭据后期实现），信任来自网络边界，UAM 必须只对 Admin Center 实例开放。spec 改了 FR-008、Clarifications 与 Assumptions；计划文档同步去掉登记端表、密钥与轮换；条目未增删，仍无 [NEEDS CLARIFICATION]。
- **2026-10-23 clarify**：确认 UAM 不基于用户会话、不使用任何用户凭据（不用 JWT），将来只有服务客户端凭据；操作人由客户端作为请求数据（`X-Operator-*` 请求头）传给 UAM。spec 改了概述、交互流程、FR-007、FR-008、FR-034、Key Entities、Clarifications 与 Assumptions；计划文档与 OpenAPI 同步（去掉 JWT 与签名密钥、写路径存活检查；修改密码改为按账号寻址）；条目未增删，仍无 [NEEDS CLARIFICATION]。
- **2026-10-23 clarify**：确认 UAM 维持领域服务（不校验用户身份），并把**静态服务密钥**纳入本期范围（取代此前「本期不做客户端凭据」）。spec 新增 User Story 12、FR-042 ~ FR-044、SC-016、关键实体「客户端」，改了概述、交互流程、FR-008、FR-034、Clarifications、Assumptions 与边界情况；计划文档同步（research R4 重写、data-model 2.4、OpenAPI 加鉴权方案、quickstart 增 S16 重写与 S19）；条目未增删，仍无 [NEEDS CLARIFICATION]。
- **2026-10-23 analyze 修复**：收窄 FR-016 与相关场景到用户级停用（账号级停用 / 删除本期没有接口）；条目数量未变，仍无 [NEEDS CLARIFICATION]。tasks.md 同步修了 T044 的跨阶段依赖、`AccountProvisioningService`、启动时快速失败、`updated_at` 赋值。
- **2026-10-27 移除 clients**：用户决定移除 UAM 的 clients 功能（服务密钥、客户端清单与权限、轮换、拒绝日志），取代 2026-10-23「静态服务密钥纳入本期」的结论；服务身份后续另行实现，UAM 目前只靠网络边界保护，`X-Operator-*` 保留。FR-042 ~ FR-044、US12、SC-016 与「客户端」实体保留编号并标注「已移除」；改密越权的 403 `client_forbidden` 提议改名 `operator_forbidden`（待用户确认）。`spec.md` 里只有这些编号的占位，本次未改；条目未增删，仍无 [NEEDS CLARIFICATION]。
- **尚未产出的交付物**：界面设计原型（FR-041 / US11 / SC-015）是本特性的一项交付物，规格只定义其范围与验收，原型本身待 `/speckit-plan` 设计阶段产出，并须经用户确认后才开始界面实现。
- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`
