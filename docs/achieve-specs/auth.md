# auth：统一认证服务（已落地的需求与领域）

状态：已实现并通过自动化验收（2026-11-23，213 个测试，行覆盖率 ≥ 90%）。过程性文档与决策依据在 `specs/001-uam-oauth2-sas/`（spec、plan、research D1–D21、data-model、tasks、quickstart、contracts）；架构与实现见 [`docs/system-design/auth.md`](../system-design/auth.md)。人工验收 SC-001、SC-006 尚待执行，结果记在 quickstart。

## 它是什么

`auth/` 是平台的统一认证中心：一个 OAuth 2.0 / OpenID Connect 授权服务器（基于 Spring Authorization Server）。它回答"你是谁"，签发令牌；**不保存角色与业务权限**，不做多租户，不做第三方登录与多因素认证。登录页由它自己提供，用户凭据只经过它。

本期只交付 auth。Portal BFF（auth-web）和各应用 BFF 是后续特性；它们对 auth 来说都只是普通 client，不需要任何特殊处理。

## 统一语言

| 术语 | 含义 |
|---|---|
| 用户（User） | 可登录的主体：唯一用户名（小写存储）、密码、启用/禁用状态、是否管理员。令牌的 `sub` 是用户 id 的十进制字符串 |
| 锁定 | 不是状态，而是 `locked_until` 在未来：同一账号连续失败 5 次锁 15 分钟 |
| client | 一个接入的应用。机密 client（有 secret，BFF）或公共 client（无 secret，只靠 PKCE） |
| SSO 会话 | 一次成功登录在 auth 建立的会话，是各应用登录状态的唯一权威。空闲 30 分钟、绝对 8 小时 |
| `sid` | 一次登录的标识：HTTP 会话 id 的 SHA-256（base64url，无填充）。写进令牌，关联授权、退出通知 |
| 授权（Authorization） | 一次授权码流程的结果，串起用户、client、scope 与由此签发的全部令牌；刷新令牌的轮换链和重用检测都以它为单位 |
| 管理员 | `is_admin` 的启用用户。只有他们能拿到 `auth.admin` scope，调用管理 API |
| `auth-admin` | bootstrap 登记的公共 client，运维工具用它登录取管理令牌 |

## 现在能做什么

**登录与令牌**
- 授权码 + PKCE（仅 S256）全流程；回调地址与 client 登记值精确匹配；`state` 必填；不展示同意页；不支持隐式、密码、client credentials。
- 令牌：访问令牌与 ID token 是 ES256 JWT；访问令牌符合 RFC 9068（`typ: at+jwt`、`iss`、`aud`、`sub`、`client_id`、`scope`、`sid`、`exp`），默认 10 分钟，不含个人资料。刷新令牌不透明，每次使用轮换。
- 刷新令牌重用检测：用过的刷新令牌再出现，整条授权链失效并写审计。刷新同时校验用户启用、改密时间、client 启用、SSO 会话仍有效；刷新算会话活动。
- 令牌有效期可按 client 配置，访问令牌必须短于刷新令牌。
- 吊销端点、UserInfo、OIDC 发现（`/.well-known/openid-configuration`、`/.well-known/oauth-authorization-server`，只通告 ES256、authorization_code、refresh_token）、`/oauth2/jwks`。

**单点登录与退出**
- 同一浏览器会话内，其他 client 的授权请求免输凭据。
- RP 发起的退出：结束 SSO 会话，删除该登录下的全部授权，回到登记的退出后地址；向登记了退出通知地址的 client 异步发 back-channel `logout_token`（重试 1 秒、5 秒、30 秒、2 分钟、10 分钟后放弃并审计）。
- 登录请求自登录页展示起 10 分钟内有效，超时提示"登录请求已过期"。

**账号安全**
- 密码用 argon2id；策略：至少 12 位、不在常见口令表、不等于用户名。登录失败的提示统一，不区分原因；被锁账号的应答与密码错误逐字相同。按来源 IP 限流（429）。
- 用户可在登录状态下改自己的密码（需当前密码）。管理员重置或 bootstrap 创建的账号首次登录必须先改密。
- 改密、禁用、管理员强制下线：结束该用户的全部登录并删除其授权。

**管理 API**（`/admin/v1`，契约 `specs/001-uam-oauth2-sas/contracts/admin-api.yaml`，仅 `auth.admin` 且管理员此刻仍启用）
- 用户：创建、列表与详情、禁用/启用、解锁、重置密码、强制下线。
- client：登记（机密 client 的 secret 只返回一次）、列表与详情（不含 secret）、PATCH（回调、退出后地址、退出通知地址、scope、令牌有效期，即时生效）、停用（同时删除其授权，不影响其他 client）/启用、secret 轮换（旧的立即失效）。
- 未带令牌 401；令牌不合格 403；错误用 RFC 9457。

**运维**
- 一次性命令（`bootstrap` profile）创建首个管理员与 `auth-admin`；密码只从控制台或标准输入读取。
- 签名密钥来自挂载文件，可有多把：第一把签名，其余仅验证，JWKS 全部发布；轮换步骤见 `auth/AGENTS.md`。
- 审计：登录成功与失败、锁定、退出、令牌签发/刷新/吊销、重用检测、用户与 client 变更（含执行的管理员），不含密码与令牌原文。应用角色对审计表只有 INSERT/SELECT。
- 过期数据定时清理（多实例只跑一个），不清理审计表。`/actuator/health` 供探活，只含状态。

## 边界与已知限制

- 不含：auth-web / 门户、业务授权、自助注册、找回密码、MFA、第三方 IdP、多租户、管理界面（只有接口）。
- 退出通知送不到时，退化为最迟在访问令牌过期（默认 10 分钟）后无法续期。
- 登录限流是进程内的，多实例下总量放宽为实例数倍；需要精确限流由网关承担。
- 直接打开登录页并登录（没有待处理的授权请求）会落在 `/`，没有主页，得到 404；登录必须由 client 的授权请求发起。
- 性能：500 并发登录 p95 约 1.7 秒（12 线程笔记本）。瓶颈是 argon2，应靠增加实例扩展，不降低哈希参数。度量须关闭 JaCoCo。
- 会话查找是对该用户的会话做哈希比对，单用户会话数很大时代价线性增长（对策见 research D17）。

## 需求与验收对照

| 需求 | 验收 |
|---|---|
| FR-001–FR-008、FR-014、FR-014a、FR-030、FR-031 | `oauth/*IT`、`session/*IT`、`logout/*IT` |
| FR-009–FR-013 | `oauth/TokenClaimsIT`、`RefreshRotationIT`、`RefreshReuseDetectionIT`、`RevocationIT` |
| FR-015–FR-020、SC-008 | `account/*`、`admin/AdminUserApiIT` |
| FR-019a、FR-021–FR-024 | `admin/AdminUserApiIT`、`admin/AdminClientApiIT`、`client/ClientIsolationIT`、`bootstrap/BootstrapAdminIT` |
| FR-025–FR-027、SC-007 | `oauth/DiscoveryAndJwksIT`、`KeyRotationIT`、`TokenVerificationTest` |
| FR-028、SC-005 | `audit/*`、`audit/LogHygieneIT` |
| FR-029 | `config/HealthEndpointIT` |
| SC-004 | `perf/LoginLoadIT`（按需运行） |
| SC-001、SC-006 | 人工验收，待执行 |
