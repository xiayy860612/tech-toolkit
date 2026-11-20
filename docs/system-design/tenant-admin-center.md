# Tenant Admin Center — 架构设计（现状）

> 当前真实的架构状态，随实现演进更新。需求与业务规则见 [`docs/achieve-specs/tenant-admin-center.md`](../achieve-specs/tenant-admin-center.md)；决策依据见 `specs/001-tenant-admin-center/research.md`。

## 1. 在平台分层中的位置

平台分层：入口层（Tenant Admin BFF、Biz BFF、Platform BFF、API Gateway）/ 租户级业务服务（UAM）/ 平台服务（Tenant Manager、App Manager）/ 基础服务（Audit、Scheduler、Notification）+ Event Bus。**除平台服务外，所有业务服务与基础服务都必须做租户隔离。**

| 本特性的组件 | 对应分层 | 说明 |
|---|---|---|
| `frontend/`（Next.js） | **Tenant Admin BFF**（入口层） | 同时承载管理界面；只调用 UAM |
| `backend/`（Spring Boot） | **UAM**（租户级业务服务） | User（含内置用户名密码）、认证、会话、审计；全部数据按租户隔离 |
| 租户 | 平台服务（Tenant Manager）的范畴 | **本期不涉及租户管理**：租户固定为 `tenant-id = 0`，UAM 内没有 `tenant` 表，仅以各表的 `tenant_id` 列标识 |
| 审计 | 基础服务（Audit）的能力 | 首期内置于 UAM，经 `AuditSink` 端口写本库 `audit_log`（带租户隔离）；日后可替换为发布到 Event Bus |
| 会话清理 | 基础服务（Scheduler）的能力 | 首期是后端进程内 `@Scheduled`，日后可迁移 |

## 2. 拓扑与请求流程

```text
浏览器 ──会话令牌(HttpOnly Cookie)──▶ BFF（frontend，对外）
                                       │  ① POST /auth/sessions/resolve（typ=service JWT）
                                       │  ② 签发 typ=user JWT（sub（整型用户 id）、tenant_id、sid、cip，EdDSA，60 秒）
                                       ▼
                               UAM（backend，仅内网）──▶ PostgreSQL（RLS）
BFF ──▶ Redis（仅登录限流计数）
```

1. 浏览器只与 BFF 交互，只持有后端签发的不透明**会话令牌**（`HttpOnly; SameSite=Lax`，`Secure` 默认开启）。
2. BFF 不保存会话数据：每次请求用令牌向后端 resolve（结果在 BFF 内存里短 TTL 缓存，默认 5 秒），得到会话身份后签发短期内部 JWT 再调用后端。
3. **后端对每个 API 请求都按 JWT 的 `sid` 校验会话仍然有效**，并核对 User 为 ACTIVE、取数据库中的当前角色，所以撤销立即生效；缓存只在 resolve 层。
4. 只有 `POST /api/v1/auth/login`、`POST /api/v1/auth/logout` 是 BFF 自有路由（设置/清除 Cookie），其余 `/api/v1/*` 是纯代理（`[...path]`）；`/auth/login`、`/auth/sessions/resolve` 仅供 BFF 以 `typ=service` 调用，通用代理对它们一律 404。

## 3. 内部 JWT

- 算法 **EdDSA（Ed25519）**；BFF 持私钥，后端只持公钥（JWK 或 JWKS 文件，支持按 `kid` 轮换）；后端拒绝 `none` 与对称算法。
- 声明：`iss=admin-center-bff`、`aud=tenant-core-api`、`jti`、`iat`、`exp`（60 秒，允许 ≤ 30 秒时钟偏差）；`typ=user` 含 `sub`（整型）、`tenant_id`、`sid`（UUID）、`cip`；`typ=service` 仅含 `tenant_id`（BFF 的 `DEFAULT_TENANT_ID`）与通用声明。**不携带 `role`**。
- 后端 `JwtAuthenticationFilter` 是唯一认证入口：验签 → 设置租户上下文（**只取自令牌**，忽略请求头/参数/请求体里的任何租户标识）→ 校验会话与账号状态 → 角色闸门（读数据库）→ 强制改密闸门。`typ=service` 令牌只能访问 `/auth/login` 与 `/auth/sessions/resolve`。
- BFF 对浏览器自带的 `X-Tenant-*`、`tenantId`、`Authorization`、`Cookie` 一律不转发、不进 JWT。

## 4. 数据与多租户隔离

- PostgreSQL 16，Flyway 迁移（`V1__init.sql`；项目未发布，已合并重写）。表：`app_user`（内置用户名、密码哈希、失败锁定）、`session`、`audit_log`；所有表带 `tenant_id bigint NOT NULL`（本期恒为 0）。`app_user`、`audit_log` 主键为 `bigint` 自增，`session.id` 为 UUID。**没有 `account` 与 `password_credential` 表**：日后的手机号/微信/OAuth2.0 Account 各用自己的表。
- **双层隔离**：① 应用层——每个事务开始时由 `TenantScopedTransactionManager` 把租户上下文写入事务级设置 `app.tenant_id`，实体用 Hibernate `@TenantId`；② 数据库层——3 张表全部启用 **Row-Level Security**，策略 `tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint`，未设置上下文时不返回也不允许写入任何行。
- 应用以非属主、非超级用户、无 `BYPASSRLS` 的角色 `tenantcore_app` 连接；迁移用属主账号执行（两者连接配置分开）。
- `UNIQUE (tenant_id, username_normalized)` 保证用户名租户内唯一；日后每种 Account 类型的表以 `(tenant_id, user_id)` 复合外键关联 `app_user` 并各自 `UNIQUE (tenant_id, user_id)`，保证每个 User 对每种类型至多一个。
- `audit_log` 对应用角色仅 `INSERT, SELECT`，触发器对任何角色拒绝更新与删除；跨租户的会话清理由属主身份的数据库函数 `purge_sessions` 完成（绕过 RLS，只清除过期/已撤销超过保留期的行）。
- 跨租户的资源一律表现为"不存在"（404，或幂等的 204），不泄露存在性。

## 5. 会话（后端统一维护）

- `session` 表：令牌 256 位随机、**只存 SHA-256**；`id`（UUID）是公开标识，`login_type` 记录登录方式；`last_seen_at` 滑动空闲、`absolute_expires_at` 绝对到期；`revoked_at`/`revoke_reason`；`ip`、`user_agent`（不对外）、`device`（由 UA 解析的「浏览器 / 系统」）。
- 撤销（`SessionService`）与审计在**同一事务**：登出、User 停用、重置密码、修改密码（保留当前会话）、管理员强制下线。因此不需要 BFF 清理钩子，也不需要会话版本号。
- 将来的 Biz BFF、Platform BFF 复用同一套"resolve → 签发 JWT → 调用"流程；会话在后端统一，管理员视图与强制下线对所有 BFF 生效。

## 6. 后端结构（Maven 单模块，包根 `tech.toolkit.tenantcore`）

| 包 | 职责 |
|---|---|
| `identity` | User（含内置用户名密码凭证）、用户管理（创建/编辑/停用/启用/解锁/重置密码）；日后的 Account 类型也落在这里 |
| `auth` | 登录判定、锁定、改密、会话（创建/解析/续期/撤销/清理）、`Authenticator` 策略 |
| `audit` | 审计事件、`AuditSink` 端口、数据库实现、查询 |
| `platform` | 租户上下文、事务租户绑定、内部 JWT 校验、密码策略与哈希、`ApiException`/`ErrorCode` |
| `api` | 仅 Web 层：控制器、JWT 认证过滤器、problem+json 错误处理 |
| `bootstrap` | 非 Web 运行模式：预置首位管理员；`dev` profile 种子 |

ArchUnit 约束：业务包不依赖 `api`；`bootstrap` 与 `api` 互不依赖；控制器不接收 `tenantId` 作为授权依据。新增登录方式（手机号、微信、其他 OAuth2.0）= 新的 Account 表（`UNIQUE (tenant_id, user_id)`）+ 新 `LoginType` 枚举值 + 新 `Authenticator`，不改 `app_user`。

## 7. 前端 / BFF 结构

`src/app/`（`(public)/login`、`(console)/…`、`api/v1/auth/{login,logout}`、`api/v1/[...path]`）、`src/server/`（仅服务端：会话 Cookie 与 resolve、内部 JWT 签发与后端客户端、限流、客户端 IP、来源校验、配置）、`src/features/`、`src/components/ui/`（shadcn 风格）、`src/lib/`（API 客户端与由契约生成的类型、zod 校验）。认证状态变化后用整页跳转，使服务端守卫重新执行。

## 8. 安全措施（摘要）

BFF 对非 GET/HEAD 请求校验 `Origin` 与 `Sec-Fetch-Site`；登录按 IP 限流（Redis，超限 429）；Cookie 属性见上；密码 Argon2id；日志与审计 `detail` 永不含密码、会话令牌、JWT；密钥与 `.keys/`、`.env.local` 不入库。详细复核见 `specs/001-tenant-admin-center/verification.md`。

## 9. 部署与配置

- **后端必须仅内网可达**：它不对浏览器暴露，也不处理 CORS/CSRF；生产须通过网络策略/内网地址限制访问（后端自身对无令牌或 `typ=service` 访问用户端点的请求一律 401，但不应依赖这一点作为唯一防线）。
- 生产迁移账号应为**非超级用户的表属主**；应用账号保持 `tenantcore_app`（由 DBA 预先创建并授予 `LOGIN`，迁移里仅在缺失时创建无登录权限的占位）。
- 后端环境变量：`DB_URL`、`DB_APP_USER`/`DB_APP_PASSWORD`、`DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD`、`LOGIN_MAX_FAILED_ATTEMPTS`、`LOGIN_LOCK_DURATION`、`SESSION_IDLE_TIMEOUT`、`SESSION_ABSOLUTE_TIMEOUT`、`SESSION_RETENTION`、`INTERNAL_JWT_ISSUER`/`_AUDIENCE`/`_PUBLIC_KEY`/`_CLOCK_SKEW`、`PASSWORD_ARGON2_MEMORY_KIB`/`_ITERATIONS`/`_PARALLELISM`。
- BFF 环境变量：`BACKEND_URL`、`REDIS_URL`、`DEFAULT_TENANT_ID`、`SESSION_COOKIE_NAME`、`COOKIE_SECURE`（生产必须为 `true`）、`SESSION_RESOLVE_CACHE_TTL_SECONDS`、`INTERNAL_JWT_PRIVATE_KEY_PATH` 等、`TRUSTED_PROXY_HOPS`、`LOGIN_RATE_LIMIT_MAX`/`_WINDOW_SECONDS`、`BACKEND_TIMEOUT_MS`。完整说明见 `backend/AGENTS.md` 与 `frontend/AGENTS.md`。
- 首位管理员：`bootstrap` 运行模式在默认租户创建，临时密码只输出一次且不进日志。

## 10. 扩展点与已知限制

- **租户管理**：届时只需让 BFF 在登录前解析出 `tenant-id` 并写入 `typ=service` 令牌，登录之后的认证、会话、隔离逻辑不变；租户状态可经 Tenant Manager 调用或 Event Bus 事件获得。
- **Audit 服务**：把 `AuditSink` 的实现换成发布到 Event Bus 即可，业务代码不变。
- **多 BFF**：复用同一套会话与 JWT 流程。
- **限制**：登录限流在 BFF（Redis），Redis 不可用时放行（并只记录错误类别）；resolve 缓存使页面级感知撤销最多滞后一个缓存周期（API 调用不受影响）；Argon2id 参数需随机型重新校准。
