---

description: "Task list for Tenant Admin Center"
---

# Tasks: Tenant Admin Center

**Input**: Design documents from `/specs/001-tenant-admin-center/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi.yaml, contracts/ui-design.md, quickstart.md

**Tests**: **必须**。根 AGENTS.md「测试先行」：每个行为任务组内，测试任务排在实现任务之前，先写出会失败的测试（红），再实现（绿），再重构。覆盖率门禁：`backend/`、`frontend/` 行覆盖率各 ≥ 90%。

**Organization**: 按 User Story 分组，每个 Story 可独立实现与验证。后端为 Maven **单模块**；前端 Next.js 同时承担 **BFF**（浏览器 ↔ BFF 用后端签发的会话令牌（Cookie）；BFF ↔ 后端用内部 JWT，见 research R4、R16）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1–US5，对应 spec.md 的 User Story
- 路径简写：后端包根 `backend/src/main/java/tech/toolkit/tenantcore/`（记为 `B/`），后端测试根 `backend/src/test/java/tech/toolkit/tenantcore/`（记为 `BT/`），前端 `frontend/src/`（记为 `F/`），前端测试 `frontend/tests/`（记为 `FT/`）

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 创建两个子项目骨架与工程门禁（根 AGENTS.md「子项目文档约定」）

- [X] T001 创建 `backend/pom.xml`：单模块，parent `spring-boot-starter-parent` 4.1.2，groupId `tech.toolkit.tenantcore`，Java 21；依赖 web、security、data-jpa、validation、flyway-core（+ PostgreSQL 驱动与 flyway-database-postgresql）、`bcprov-jdk18on` 1.81、`nimbus-jose-jwt` 10.5；测试依赖 spring-boot-starter-test、Testcontainers PostgreSQL、`archunit-junit6` 1.5.1；JaCoCo 0.8.13（`verify` 阶段 BUNDLE 行覆盖率 `COVEREDRATIO` ≥ 0.90，仅排除 `**/*Application.class`）、Spotless 2.44.5 + google-java-format 1.27.0（`verify` 阶段 check）、surefire 同时包含 `**/*Test.java` 与 `**/*IT.java`；并创建启动类 `B/TenantCoreApplication.java` 与空包 `identity/ auth/ audit/ platform/ api/ bootstrap/`
- [X] T002 [P] 创建 `backend/AGENTS.md` 与 `backend/CLAUDE.md`（内容 `@AGENTS.md`）：技术栈与目录说明；dev/lint/typecheck(compile)/test/build 命令；覆盖率口径（行覆盖率 ≥ 90%）与排除路径；环境变量（数据库、锁定阈值、内部 JWT 的 issuer/audience/公钥路径/时钟偏差、会话空闲/绝对超时）；说明后端仅内网可达、只接受 BFF 签发的 JWT
- [ ] T003 [P] 创建前端工程 `frontend/package.json`（name `admin-center-web`，`private: true`，`type: module`，`packageManager` 为 pnpm，`engines.node >=22`；scripts：`dev` `build` `start` `lint`（`eslint . && prettier --check .`）`typecheck`（`tsc --noEmit`）`test`（`vitest run`）`test:coverage`（`vitest run --coverage`）`test:e2e`（`playwright test`）`generate:api`）。dependencies：`@tailwindcss/postcss ^4.3.5`、`class-variance-authority ^0.7.1`、`clsx ^2.1.1`、`ioredis ^5.4.1`、`lucide-react ^1.70.0`、`next ^15.5.0`、`postcss ^8.5.32`、`radix-ui ^1.8.1`、`react ^19.1.0`、`react-dom ^19.1.0`、`tailwind-merge ^3.7.0`、`tailwindcss ^4.3.5`、`tw-animate-css ^1.4.0`，另加 `zod`、`jose`。devDependencies：`@eslint/eslintrc ^3.3.1`、`@playwright/test ^1.50.0`、`@testing-library/jest-dom ^6.6.3`、`@testing-library/react ^16.3.0`、`@testing-library/user-event ^14.6.1`、`@types/node ^22.10.0`、`@types/react ^19.1.0`、`@types/react-dom ^19.1.0`、`@vitejs/plugin-react ^4.5.0`、`@vitest/coverage-v8 ^3.2.0`、`eslint ^9.20.0`、`eslint-config-next ^15.5.0`、`eslint-config-prettier ^10.1.0`、`ioredis-mock ^8.9.0`、`jsdom ^26.1.0`、`prettier ^3.5.0`、`typescript ^5.7.0`、`vitest ^3.2.0`，另加 `openapi-typescript`；**不含** lighthouse/puppeteer-core/chrome-launcher。并创建 `tsconfig.json`、`eslint.config.mjs`、`.prettierrc`、`postcss.config.mjs`、`vitest.config.ts`（jsdom、`@vitest/coverage-v8`，行覆盖率阈值 90%，排除 `F/lib/api/schema.d.ts`）、`playwright.config.ts`、`next.config.ts`（仅 `output: "standalone"`，**不配置 rewrites**）
- [ ] T004 [P] 创建 `frontend/AGENTS.md` 与 `frontend/CLAUDE.md`（内容 `@AGENTS.md`）：栈与目录；命令；覆盖率与排除路径；环境变量；BFF 职责说明
- [X] T005 [P] 创建 `backend/compose.yaml`（本地 PostgreSQL 16 与 Redis 7）与 `backend/.env.example`（数据库连接、锁定阈值、会话空闲/绝对超时与保留期、内部 JWT 参数；仅示例值，不含真实密钥）
- [ ] T006 [P] 创建 `frontend/.env.example`：`BACKEND_URL`、`REDIS_URL`、`DEFAULT_TENANT_ID=0`（本期固定默认租户，无租户管理）、会话 Cookie 名、`SESSION_RESOLVE_CACHE_TTL_SECONDS`（默认 5，0 关闭会话解析缓存）、内部 JWT 私钥路径与 issuer/audience/有效期（默认 60 秒）、可信代理配置（`TRUSTED_PROXY_HOPS`：信任的反向代理层数，默认 0 = 不信任 `X-Forwarded-For`、直接取连接地址）
- [ ] T007 [P] 创建 `frontend/scripts/gen-internal-jwt-keys.mjs`：生成开发用 Ed25519 密钥对到被 `.gitignore` 忽略的 `.keys/`（私钥给 BFF，公钥给后端）；在仓库 `.gitignore` 加入 `.keys/`，确保任何密钥不入库
- [ ] T008 由契约生成前端类型：配置 `generate:api`（openapi-typescript，输入 `specs/001-tenant-admin-center/contracts/openapi.yaml`（后端契约）与 `contracts/bff-openapi.yaml`（BFF 自有接口）），产出 `F/lib/api/schema.d.ts`（提交入库，标注为生成文件）
- [ ] T009 [P] 创建 CI：`.github/workflows/backend.yml`（`verify`：编译、Spotless、测试、JaCoCo 90% 门禁）与 `.github/workflows/frontend.yml`（lint、typecheck、test:coverage、build）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有 User Story 共用的数据库、租户上下文、内部 JWT 校验、错误模型、审计，以及前端基础与 BFF 基础设施

**⚠️ CRITICAL**: 本阶段完成前不得开始任何 User Story

### 后端：先写测试

- [X] T010 [P] 创建 `BT/ArchitectureTest.java`（ArchUnit）：业务包 `identity/auth/audit/platform` 不得依赖 `api`；`bootstrap` 与 `api` 互不依赖
- [X] T011 [P] 创建 `BT/support/AbstractPostgresIT.java`：Testcontainers PostgreSQL 16 + Flyway 迁移 + 以**非超级用户、非表属主**角色连接（保证 RLS 生效）
- [X] T012 [P] 创建 `BT/migration/SchemaConstraintsIT.java`（先失败）：`UNIQUE (tenant_id, type, identifier_normalized)`；复合外键 `(tenant_id, user_id) → app_user(tenant_id, id)`；`session.token_hash` 唯一、`session` 表启用 RLS；`audit_log` 的 UPDATE/DELETE 被触发器拒绝；RLS 使未设置 `app.tenant_id` 时业务表返回 0 行

### 后端：实现

- [X] T013 创建 `backend/src/main/resources/db/migration/V1__init.sql`，严格按 data-model.md：表 `app_user`、`account`、`password_credential`、`session`、`audit_log`（**无 `tenant` 表**——本期不涉及租户管理，租户仅由各表的 `tenant_id bigint not null` 标识，恒为 0）；约束逐字落地——`app_user.display_name` 非空 ≤ 50、`phone` 可空（11 位大陆手机号）、`email` 可空、`role` ∈ `TENANT_ADMIN/MEMBER`、`version bigint`；`account.identifier` ≤ 64、`identifier_normalized`、`type` 首期仅 `USERNAME_PASSWORD`、`failed_attempts int` 默认 0、`locked_until`、`version`；`password_credential.must_change_password`；`session.token_hash bytea` 唯一、`session.user_agent` ≤ 512、`revoke_reason` ∈ `LOGOUT/USER_DISABLED/ACCOUNT_DISABLED/ACCOUNT_DELETED/PASSWORD_RESET/PASSWORD_CHANGED/ADMIN_REVOKED`；`audit_log` 仅授予 `INSERT, SELECT` + 触发器拒绝 UPDATE/DELETE，`target_type` ∈ `USER/ACCOUNT`；五张表全部启用 RLS，策略 `tenant_id = current_setting('app.tenant_id')::bigint`；创建索引（见 data-model.md 各表「索引」）（使 T012 通过）
- [X] T014 [P] 创建实体与仓储：`B/identity/AppUser.java`、`B/identity/Account.java`、`B/identity/PasswordCredential.java` 及对应 `*Repository.java`（字段、长度、可空性与 T013 一致；`tenantId` 为 `long`；`AppUser`、`Account` 带 `@Version`；**无 Tenant 实体**）
- [X] T015 [P] 创建实体与仓储：`B/audit/AuditLog.java`、`B/auth/Session.java`（`revoke_reason` 枚举 `LOGOUT/USER_DISABLED/ACCOUNT_DISABLED/ACCOUNT_DELETED/PASSWORD_RESET/PASSWORD_CHANGED/ADMIN_REVOKED`；`tokenHash` 只存哈希）及仓储
- [X] T016 [P] 创建 `BT/platform/TenantContextIT.java`（先失败）：事务内设置租户上下文后只能读到本租户行；未设置返回空；切换上下文后读不到上一租户数据
- [X] T017 创建 `B/platform/TenantContext.java` 与事务内 `SET LOCAL app.tenant_id` 机制（`B/platform/TenantScopedTransaction.java`）（使 T016 通过）
- [X] T018 [P] 创建 `BT/platform/PasswordPolicyTest.java` 与 `BT/platform/PasswordHasherTest.java`（先失败）：长度 ≥ 10、含大小写/数字/符号至少 3 类、不得含用户名、不得等于临时密码；Argon2id 哈希可校验、哈希串不含明文；系统生成临时密码为 16 位随机
- [X] T019 创建 `B/platform/PasswordPolicy.java`、`B/platform/PasswordHasher.java`（Spring Security `Argon2PasswordEncoder` + Bouncy Castle）、`B/platform/TemporaryPasswordGenerator.java`（使 T018 通过）
- [X] T020 [P] 创建 `BT/platform/PasswordHasherBenchmarkTest.java`（`@Tag("performance")`，默认不在 CI 主流程运行）：在参考环境断言单次 Argon2id 哈希耗时在 100–400ms 之间，以保证登录 p95 ≤ 1 秒（plan Performance Goals）；Argon2id 参数（内存、迭代、并行度）可配置并记录在 `backend/AGENTS.md`
- [X] T021 [P] 创建 `BT/api/ProblemErrorHandlerTest.java`（先失败）：错误输出 `application/problem+json`，含稳定 `code`；校验失败含 `errors[{field,message}]`；覆盖 contracts/openapi.yaml 中全部 `code`：`INVALID_CREDENTIALS`、`FORBIDDEN`、`PASSWORD_CHANGE_REQUIRED`、`USERNAME_TAKEN`、`VERSION_CONFLICT`、`LAST_ADMIN`、`CANNOT_DISABLE_SELF`、`LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED`、`VALIDATION_FAILED`、`NOT_FOUND`、`CANNOT_REVOKE_OWN_SESSIONS`
- [X] T022 创建 `B/api/ErrorCode.java`、`B/api/ApiException.java`、`B/api/ProblemErrorHandler.java`（使 T021 通过）
- [X] T023 [P] 创建 `B/platform/AdminCenterProperties.java`（`@ConfigurationProperties`）与 `backend/src/main/resources/application.yml`：锁定阈值默认 5 次、锁定时长默认 15 分钟、会话空闲超时默认 30 分钟、绝对超时默认 8 小时、已撤销/过期会话保留期默认 7 天（均可配置）、内部 JWT（issuer `admin-center-bff`、audience `tenant-core-api`、公钥来源、时钟偏差 ≤ 30 秒）；全部可由环境变量覆盖
- [X] T024 [P] 创建 `BT/audit/AuditServiceIT.java`（先失败）：写入 `action` 枚举全集（`LOGIN_SUCCESS`、`LOGIN_FAILURE`、`LOGOUT`、`PASSWORD_CHANGED`、`ACCOUNT_LOCKED`、`ACCOUNT_UNLOCKED`、`USER_CREATED`、`USER_UPDATED`、`USER_DISABLED`、`USER_ENABLED`、`ACCOUNT_CREATED`、`ACCOUNT_DISABLED`、`ACCOUNT_ENABLED`、`ACCOUNT_DELETED`、`PASSWORD_RESET`、`SESSION_REVOKED`、`SESSIONS_REVOKED_ALL`）；记录来自 JWT `cip` 的 IP；`detail` 永不含密码/令牌（传入敏感键被脱敏）；失败登录以独立事务落库
- [X] T025 创建 `B/audit/AuditService.java`、`B/audit/AuditAction.java` 与端口 `B/audit/AuditSink.java`（首期实现写本库 `audit_log`，日后可换为发布到 Audit 基础服务；业务代码只依赖 `AuditSink`/`AuditService`）（使 T024 通过）
- [X] T026 [P] 创建 `BT/auth/SessionServiceIT.java`（先失败）：令牌 256 位随机、库内只存 SHA-256 哈希；`create` 返回一次性令牌与到期时间；`resolve` 校验有效并续期（空闲 30 分钟滑动，`last_seen_at` 每分钟最多写一次；绝对 8 小时）；按会话 id、按 Account、按 User、除当前会话外 撤销并记录 `revoke_reason`；已撤销/已过期会话 `resolve` 失败；过期与撤销超过保留期（默认 7 天）的会话被定时清理；创建会话时保存 `ip`（来自 service JWT 的 `cip`）与 `user_agent`，设备摘要由 User-Agent 解析为「浏览器 / 系统」，原始 UA 不对外
- [X] T027 创建 `B/auth/SessionService.java`、`B/auth/SessionCleanupJob.java`（`@Scheduled`）与 `B/auth/DeviceParser.java`（使 T026 通过）
- [X] T028 [P] 创建 `BT/platform/InternalJwtVerifierTest.java`（先失败）：合法 EdDSA 令牌通过并解析出 `sub`、`tenant_id`（数字）、`account_id`、`sid`、`cip`；`iss`/`aud` 不符、已过期（超出 30 秒偏差）、签名无效（错误密钥）、`alg=none`、对称算法（HS256）、缺少必需声明均被拒；`typ=service`（仅含 `tenant_id` 与通用声明）与 `typ=user` 区分；支持按 `kid` 轮换公钥
- [X] T029 创建 `B/platform/InternalJwtVerifier.java`（`nimbus-jose-jwt`，仅白名单 EdDSA）与 `B/platform/InternalJwtClaims.java`（使 T028 通过）
- [X] T030 [P] 创建 `BT/api/JwtAuthenticationFilterIT.java`（先失败；使用测试专用桩控制器 `BT/support/StubController.java`，提供 `/auth/login`、`/auth/sessions/resolve`、`/auth/me`、`/auth/change-password`、`/auth/logout` 与仅管理员可访问的 `/_test/admin`，不依赖后续阶段的真实端点）：`typ=user` 令牌设置 `TenantContext` 仅取自 `tenant_id` 声明，忽略请求头/参数/请求体里的任何租户标识；无令牌/无效/过期 → 401；User 或 Account 已停用 → 401；`sid` 对应会话已撤销/已过期（空闲或绝对）/不存在、或与 `sub`/`tenant_id`/`account_id` 不符 → 401；有效请求续期会话；`typ=service` 令牌只能访问 `/auth/login` 与 `/auth/sessions/resolve`，访问用户端点 → 401；`MEMBER` 访问 `/_test/admin` → 403 `FORBIDDEN`；角色取自数据库当前值——管理员被降级后，同一令牌的下一次请求访问 `/_test/admin` → 403；库中 `must_change_password=true` 时除 `/auth/me`、`/auth/change-password`、`/auth/logout` 外 → 403 `PASSWORD_CHANGE_REQUIRED`
- [X] T031 创建 `B/api/security/JwtAuthenticationFilter.java` 与 `B/api/security/SecurityConfig.java`：验签 → 设置租户上下文 → 校验 `sid` 对应会话有效且与声明一致（并续期）、核对库中状态（User 与 Account 均 `ACTIVE`）与用户当前角色 → 角色闸门（授权角色以数据库为准，不使用令牌声明）→ 强制改密闸门；关闭 Servlet HttpSession/CSRF/表单登录（后端无状态、不面向浏览器）（依赖 T017、T029、T027；使 T030 通过）

### 前端：UI 与客户端基础（先写测试）

- [ ] T032 [P] 创建 `FT/unit/ui.test.tsx`（先失败）：`Button`、`Input`、`Field`（label + 错误文案，`aria-invalid` + `aria-describedby`）、`Badge`（四种状态色）、`Card` 的渲染与可访问性
- [ ] T033 创建 `F/app/globals.css`（Tailwind 4 `@import` + `@theme`：ui-design.md「设计 Token」全部取值）、`F/lib/utils.ts`（`cn`）、`F/components/ui/{button,input,field,badge,card}.tsx`（使 T032 通过）
- [ ] T034 [P] 创建 `FT/unit/validation.test.ts`（先失败）：用户名 `^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$`；手机号 `^1[3-9]\d{9}$`；邮箱；显示名非空 ≤ 50；密码复杂度（长度 ≥ 10，含大小写/数字/符号至少 3 类）
- [ ] T035 创建 `F/lib/validation.ts`（zod schema，规则与后端一致）（使 T034 通过）
- [ ] T036 [P] 创建 `FT/unit/api-client.test.ts`（先失败）：浏览器端 fetch 封装把 problem+json 映射为带 `code` 的 `ApiError`；401 触发会话过期回调（跳转 `/login?next=<原目标页>`）；请求只发往同源 `/api/v1`
- [ ] T037 创建 `F/lib/api/client.ts`、`F/lib/api/errors.ts`（使 T036 通过；类型来自 T008 的 `schema.d.ts`）

### 前端：BFF 基础设施（先写测试）

- [ ] T038 [P] 创建 `FT/unit/session-cookie.test.ts`（先失败）：登录成功后设置 Cookie：`HttpOnly; Secure; SameSite=Lax; Path=/`，值为后端返回的不透明令牌，`Max-Age` 取自后端 `expiresAt`；响应体不含令牌；清除 Cookie（`Max-Age=0`）；可从请求读取令牌
- [ ] T039 创建 `F/server/session/cookie.ts`（使 T038 通过）
- [ ] T040 [P] 创建 `FT/unit/internal-jwt.test.ts`（先失败）：`typ=user` 令牌含 `sub`、`tenant_id`（数字）、`account_id`、`sid`、`cip`、`iss=admin-center-bff`、`aud=tenant-core-api`、`jti`、`iat`、`exp`（有效期 60 秒）；`typ=service` 令牌含 `tenant_id`（取自 `DEFAULT_TENANT_ID`）与通用声明，不含用户声明；EdDSA 签名可由公钥验证；每次签发 `jti` 不同
- [ ] T041 创建 `F/server/backend/jwt.ts`（`jose`，私钥来自环境变量指定的文件）（使 T040 通过）
- [ ] T042 [P] 创建 `FT/unit/rate-limit.test.ts`（`ioredis-mock`，先失败）：按客户端 IP 的固定窗口计数，超限返回需等待秒数，窗口过后恢复；不同路由组计数互相独立；`client-ip` 仅在可信代理配置下采用 `X-Forwarded-For`，否则取连接地址
- [ ] T043 创建 `F/server/rate-limit.ts`（Redis `INCR` + `EXPIRE`）与 `F/server/client-ip.ts`（使 T042 通过）
- [ ] T044 [P] 创建 `FT/unit/backend-client.test.ts`（先失败）：服务端后端客户端按需签发 `typ=user`/`typ=service` JWT 并带 `Authorization: Bearer`；设置超时；后端 problem+json 原样透传；绝不转发浏览器的 `Cookie` 与 `Authorization` 头；不把 JWT 写入日志
- [ ] T045 创建 `F/server/backend/client.ts`（使 T044 通过）
- [ ] T046 [P] 创建 `FT/unit/session-resolver.test.ts`（先失败）：以 `typ=service` JWT 调后端 `POST /auth/sessions/resolve`；成功结果按令牌哈希做内存缓存，TTL 为 `SESSION_RESOLVE_CACHE_TTL_SECONDS`（默认 5 秒，0 关闭缓存）；后端 401 → 视为无会话并清除缓存；日志中绝不出现令牌
- [ ] T047 创建 `F/server/session/resolver.ts`（依赖 T041、T045；使 T046 通过）
- [ ] T048 [P] 创建 `FT/unit/proxy-route.test.ts`（先失败）：无会话 Cookie → 401 problem+json；有 Cookie → 经会话解析器取得会话身份（`sid`、用户、租户），以此签发 JWT 转发后端并回传状态与响应体；非 GET/HEAD 请求的 `Origin` 与自身来源不一致或 `Sec-Fetch-Site` 非 `same-origin` → 403；resolve 或后端返回 401 → 清除会话 Cookie；浏览器自带的 `X-Tenant-*`、`tenantId`、`Authorization` 不进入 JWT 与转发请求；改密、会话管理等均为纯代理，BFF 不持有会话状态
- [ ] T049 创建 `F/app/api/v1/[...path]/route.ts`（通用代理，使 T048 通过）

**Checkpoint**: 数据库、租户上下文、内部 JWT 校验、错误模型、审计、前端基础与 BFF 基础设施就绪

---

## Phase 3: User Story 1 - 用户名密码登录并进入管理中心 (Priority: P1) 🎯 MVP

**Goal**: 登录页 → 登录（含锁定、统一失败提示、强制改密）→ 进入用户列表；可退出；会话空闲/绝对超时。本期租户固定为 `tenant-id = 0`，无租户入口。

**Independent Test**: 用 `bootstrap` 在默认租户预置管理员；正确凭证可进入用户列表，错误凭证被拒且提示统一；临时密码首登被强制改密（quickstart 场景 1、2、3、4）。

### Tests for User Story 1 ⚠️（先写，确认失败）

- [X] T050 [P] [US1] 创建 `BT/auth/LoginServiceTest.java`：判定顺序「Account 存在 → Account ACTIVE → User ACTIVE → 未锁定 → 密码匹配」，任一失败返回同一 `INVALID_CREDENTIALS` 结果；失败累加 `failed_attempts`，达 5 次设置 `locked_until = now + 15min`；锁定期间正确密码也拒绝；成功清零、更新 `last_login_at`，创建会话并返回一次性会话令牌；用户名不存在时仍执行一次哑哈希比较；账号查找限定在 JWT 给出的租户内
- [X] T051 [P] [US1] 创建 `BT/api/AuthApiContractIT.java`：以 `typ=service` JWT（`tenant_id=0`）调用 `/auth/login`，响应（`LoginResult`，含一次性 `sessionToken` 与 `expiresAt`）通过 `contracts/openapi.yaml` 校验，请求体不含租户字段，库里只存令牌哈希；用户名不存在、密码错、Account 停用、User 停用、被锁定五种失败的 401 响应体完全一致；`/auth/sessions/resolve`：有效令牌 → 200 并续期，无效/过期/已撤销 → 401；以 `typ=user` JWT 调用 `/auth/me`（`role` 取自数据库当前值）、`/auth/logout`（撤销当前会话并记审计 `LOGOUT`，之后该会话的请求 → 401）、`/auth/change-password`（成功 204，同账号其他会话被撤销、当前会话保留，`mustChangePassword` 置 false）；当前密码错误返回 422
- [X] T052 [P] [US1] 创建 `BT/bootstrap/BootstrapRunnerIT.java`：在默认租户（`tenant_id=0`）创建首位 `TENANT_ADMIN`，系统生成 16 位临时密码仅输出一次到标准输出、不进入日志，`must_change_password=true`；显示名缺省为用户名；重复用户名被拒；不创建任何租户实体

### Implementation for User Story 1（后端）

- [X] T053 [US1] 创建 `B/auth/Authenticator.java`（策略接口，按 Account `type` 分派）与 `B/auth/PasswordAuthenticator.java`（依赖 T019）
- [X] T054 [US1] 创建 `B/auth/LoginService.java`：登录判定、锁定/解锁计数、审计 `LOGIN_SUCCESS/LOGIN_FAILURE/ACCOUNT_LOCKED`、创建会话（`SessionService`，传入 `cip` 与登录请求中的 `userAgent`）并返回一次性令牌（依赖 T025、T053、T027；使 T050 通过）
- [X] T055 [US1] 创建 `B/auth/PasswordChangeService.java`：校验当前密码、复杂度、不得与当前/临时密码相同；成功后 `must_change_password=false`，撤销该 Account 除当前会话（`sid`）外的全部会话，审计 `PASSWORD_CHANGED`（依赖 T019、T027）
- [X] T056 [US1] 创建控制器 `B/api/AuthController.java`，按 `contracts/openapi.yaml` 实现 `/auth/*`（含 `/auth/sessions/resolve`；登出撤销当前会话）（使 T051 通过）
- [X] T057 [US1] 创建 `B/bootstrap/BootstrapRunner.java`：以启动参数/profile `bootstrap` 触发的非 Web 运行模式，接受管理员用户名与显示名（`display_name` 必填，缺省取用户名），在默认租户（`tenant_id=0`）创建首位 `TENANT_ADMIN`，输出一次性临时密码后退出（使 T052 通过）
- [X] T058 [P] [US1] 创建 `B/bootstrap/DevSeedRunner.java`（仅 `dev` profile）：在租户 0 预置管理员，测试凭证明示于 `backend/src/main/resources/application-dev.yml`；`test` profile 另预置租户 1 的夹具用户仅供隔离测试（本期无租户管理，租户 1 只存在于测试环境）

### Tests for User Story 1（BFF 专用路由）⚠️

- [ ] T059 [P] [US1] 创建 `FT/unit/login-route.test.ts`：先按 IP 限流（超限 429 + `Retry-After`）；以 `typ=service` JWT（`tenant_id` 取自环境变量 `DEFAULT_TENANT_ID`，默认 0）调用后端登录，并传入浏览器 User-Agent；后端 401 原样返回统一错误且不设置 Cookie；成功则设置会话 Cookie（令牌取自后端响应），响应体只含用户信息，**不含** `sessionToken`/`tenantId` 等内部字段
- [ ] T060 [P] [US1] 创建 `FT/unit/logout-route.test.ts`：携带有效会话时以用户 JWT 调后端登出（后端撤销会话并记审计）后清除 Cookie；后端已判定会话无效时仍清除 Cookie；无会话 Cookie 时幂等成功

### Implementation for User Story 1（BFF 专用路由）

- [ ] T061 [US1] 创建 `F/app/api/v1/auth/login/route.ts`（使 T059 通过）
- [ ] T062 [P] [US1] 创建 `F/app/api/v1/auth/logout/route.ts`（使 T060 通过）

### Tests for User Story 1（前端页面）⚠️

- [ ] T063 [P] [US1] 创建 `FT/component/LoginPage.test.tsx`：账号框标签为「用户名」，页面**没有任何租户相关输入**；**不含**「忘记密码」「企业 SSO」「扫码登录」「7 天内免登录」；失败统一显示「用户名或密码错误」；成功后跳转（强制改密时到 `/change-password`，否则到 `next` 或 `/users`）
- [ ] T064 [P] [US1] 创建 `FT/component/ChangePasswordPage.test.tsx`：复杂度规则实时勾选；两次输入不一致提示；当前密码错误（422）提示；成功后进入用户列表
- [ ] T065 [P] [US1] 创建 `FT/component/AppShell.test.tsx` 与 `FT/unit/middleware.test.ts`：侧边栏仅「用户管理」「会话管理」；用户菜单含「修改密码」「退出登录」，退出调用 `/api/v1/auth/logout`；无会话 Cookie 访问受保护路径时跳转 `/login?next=<原目标页>`；`/` 重定向到 `/users`
- [ ] T066 [P] [US1] 创建 `FT/component/ForbiddenGuard.test.tsx`：`/auth/me` 返回的角色非 `TENANT_ADMIN`（`MEMBER`）登录后被重定向到 `/forbidden`；外壳仅显示「修改密码」「退出登录」，不显示侧边栏导航；角色以 `/auth/me` 为准，不信会话中缓存的 `role`

### Implementation for User Story 1（前端页面）

- [ ] T067 [P] [US1] 创建 `F/components/AuthLayout.tsx`（左品牌区 `#0F1629` + 右表单区，取自设计稿 Login；供登录页与强制改密页复用）
- [ ] T068 [P] [US1] 创建 `F/app/(public)/login/page.tsx` 与 `F/features/auth/LoginForm.tsx`（品牌文案改为多租户隔离/多登录方式 Account/操作审计，见 ui-design.md「裁剪」）
- [ ] T069 [US1] 创建 `F/app/(console)/layout.tsx`、`F/components/AppShell.tsx`（Sidebar 232px 含「用户管理」「会话管理」、TopBar 面包屑 + 用户菜单）与服务端会话守卫（经会话解析器（resolver）解析会话 Cookie → 以用户 JWT 取 `/auth/me`；`mustChangePassword` 时重定向；角色非 `TENANT_ADMIN` 时重定向到 `/forbidden`，使 ForbiddenGuard 测试通过），`F/app/page.tsx` 重定向到 `/users`
- [ ] T070 [US1] 创建 `F/app/(console)/change-password/page.tsx` 与 `F/features/auth/ChangePasswordForm.tsx`
- [ ] T071 [US1] 创建 `F/app/(console)/forbidden/page.tsx` 与 `F/middleware.ts`（缺会话 Cookie 跳转 `/login` 并保留 `next`；鉴权以后端校验 JWT 为准）
- [ ] T072 [US1] 创建 E2E `FT/e2e/login-journey.spec.ts`：登录 → 强制改密 → 用户列表；会话 Cookie 为 HttpOnly 且浏览器端拿不到 JWT；从打开登录页到进入用户列表 ≤ 10 秒（SC-001）；`MEMBER` 登录被导向 `/forbidden`（quickstart 场景 1、2、13）

**Checkpoint**: User Story 1 可独立运行与验证（MVP）

---

## Phase 4: User Story 4 - 租户数据隔离 (Priority: P1)

**Goal**: 任何路径都无法跨租户读写；同名用户名在不同租户互不冲突；后端只信已验签 JWT 里的租户。隔离在 Phase 2 起已由 RLS 兜底，本阶段补全应用层强制与验证套件，并随 US2/US3 持续扩展。

**Independent Test**: 租户 0 的管理员（及测试夹具租户 1 的管理员）：用租户 0 的会话读/改/停用租户 1 的数据全部 404、0 泄露；绕过 BFF 直连后端、伪造或篡改 JWT 一律 401（quickstart 场景 12、18；SC-003）。

### Tests for User Story 4 ⚠️

- [X] T073 [P] [US4] 创建 `BT/isolation/TenantIsolationIT.java`（跨租户隔离测试集的基础，使用租户 0 与测试夹具租户 1）：RLS 下无上下文返回 0 行；上下文为租户 0 时读不到租户 1 的行、也无法写入租户 1 的行；请求头/参数/请求体里携带的任何租户标识都不影响授权；篡改 `tenant_id` 后签名失效的 JWT → 401；`/auth/me` 只返回调用者本人
- [X] T074 [P] [US4] 创建 `BT/isolation/SameUsernameAcrossTenantsIT.java`：租户 0 与夹具租户 1 存在同名用户名，各自登录（service JWT 的 `tenant_id` 不同）进入各自租户，互不冲突（US4-3、FR-014）
- [ ] T075 [P] [US4] 创建 `FT/unit/proxy-tenant.test.ts`：BFF 签发的 JWT 租户只取自后端 resolve 返回的会话身份；浏览器请求里的 `X-Tenant-*`、`tenantId`（查询串、请求体）既不改变 JWT 也不被转发

### Implementation for User Story 4

- [ ] T076 [US4] 配置 Hibernate 多租户判别（`@TenantId` 加在租户范围实体上，`CurrentTenantIdentifierResolver` 取自 `TenantContext`），并保证所有仓储查询带租户条件；跨租户 id 统一映射为 404 `NOT_FOUND`（使 T073、T074 通过）
- [X] T077 [P] [US4] 在 `BT/ArchitectureTest.java` 增加规则：`api` 包控制器的方法参数与请求体不得出现名为 `tenantId` 的授权依据字段

**Checkpoint**: 隔离基线完成；US2/US3 阶段各含一条「扩展隔离套件」任务

---

## Phase 5: User Story 2 - 管理租户内的用户（User） (Priority: P2)

**Goal**: 租户管理员查看/搜索/创建/编辑/停用/启用本租户用户，创建时同时开通首个用户名密码 Account。

**Independent Test**: 管理员创建用户（系统生成临时密码）→ 该用户可登录并被强制改密；停用后无法登录（quickstart 场景 5、6、10、11）。

### Tests for User Story 2 ⚠️

- [X] T078 [P] [US2] 创建 `BT/identity/UserServiceTest.java`：创建用户同时创建 `USERNAME_PASSWORD` Account 且 `must_change_password=true`；`display_name` 非空 ≤ 50；`phone` 匹配 `^1[3-9]\d{9}$`；用户名匹配 `^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$` 且租户内大小写不敏感唯一（冲突 → `USERNAME_TAKEN`）；乐观锁冲突 → `VERSION_CONFLICT`；停用自己 → `CANNOT_DISABLE_SELF`；停用/降级最后一名管理员 → `LAST_ADMIN`（事务内 `FOR UPDATE` 守卫）；停用用户时撤销其全部会话（`USER_DISABLED`；既有会话下一次请求 401，重新启用后也不复活）；全部操作写审计
- [X] T079 [P] [US2] 创建 `BT/api/UsersApiContractIT.java`：`GET/POST /users`、`GET/PATCH /users/{id}`、`POST /users/{id}/disable|enable` 响应通过契约校验；`keyword`（≤ 50）、`status`、`page`（≥ 0）、`size`（1–100，默认 20）；系统生成的临时密码仅在创建响应返回一次；`MEMBER` → 403 `FORBIDDEN`；`must_change_password=true` 的会话访问 → 403 `PASSWORD_CHANGE_REQUIRED`
- [X] T080 [P] [US4] 在 `BT/isolation/TenantIsolationIT.java` 增加用户端点用例：A 的 JWT 按 B 的用户 id 调用 GET/PATCH/disable/enable 全部 404；列表与搜索只含 A 的用户
- [ ] T081 [P] [US2] 创建 `FT/component/UsersListPage.test.tsx`：筛选栏（关键词 + 状态）、页签「全部/启用/停用」、表头「姓名、主用户名（含 +N 个账号）、角色、状态、最近登录、操作」、分页「共 N 条，每页 M 条」、空态与加载失败重试；**不含**批量操作条与导出
- [ ] T082 [P] [US2] 创建 `FT/component/UserForm.test.tsx`：分组卡片「基本信息」（姓名\*、手机号、邮箱）与「账号与角色」（角色 2 选 1、登录用户名\*、临时密码输入或「系统生成」）；**不含**所属部门、数据权限范围、备注、发送激活通知；吸底栏显示「有 N 项校验未通过」；创建成功弹出一次性临时密码框（复制按钮 + 关闭后不可再次查看）；`USERNAME_TAKEN` 行内提示；编辑页不含用户名/密码，`VERSION_CONFLICT` 提示刷新
- [ ] T083 [P] [US2] 创建 `FT/component/UserDetailPage.test.tsx`：用户信息卡、启用/停用（停用自己、最后一名管理员的错误提示按 `code` 映射）

### Implementation for User Story 2

- [X] T084 [US2] 创建 `B/identity/UserService.java`（创建、更新、停用/启用、最后管理员守卫、撤销会话、审计；依赖 T014、T025、T027）
- [X] T085 [P] [US2] 创建 `B/identity/UserQueryRepository.java`（关键词匹配显示名或用户名前缀、状态筛选、分页，均受租户范围约束）
- [X] T086 [US2] 创建 `B/api/UserController.java` 与请求/响应 DTO（`CreateUserRequest`、`UpdateUserRequest`、`User`、`UserPage`、`CreateUserResult`，字段与 openapi.yaml 一致）（使 T078、T079、T080 通过）
- [ ] T087 [P] [US2] 创建 `F/components/ui/{table,pagination,dialog,switch,tabs,sticky-action-bar}.tsx`（Radix 原语；分页按钮 44px；取自设计稿 List/Form）
- [ ] T088 [US2] 创建 `F/app/(console)/users/page.tsx` 与 `F/features/users/UsersTable.tsx`（使 T081 通过）
- [ ] T089 [US2] 创建 `F/app/(console)/users/new/page.tsx`、`F/app/(console)/users/[id]/edit/page.tsx`、`F/features/users/UserForm.tsx`、`F/features/users/TempPasswordDialog.tsx`（使 T082 通过）
- [ ] T090 [US2] 创建 `F/app/(console)/users/[id]/page.tsx`（用户信息 + 启用/停用；Account 区在 US3 补全；**用户详情页不含会话区**——会话管理是独立页面 `/sessions`，见 US5）（使 T083 通过）
- [ ] T091 [US2] 创建 E2E `FT/e2e/users-journey.spec.ts`：创建用户 → 新用户登录并强制改密；用户名冲突提示；同名用户名在另一租户可创建（quickstart 场景 5、6）

**Checkpoint**: US1 + US2 可独立工作

---

## Phase 6: User Story 3 - 管理 User 的多个登录 Account (Priority: P2)

**Goal**: 一个 User 可有多个 Account；管理员可新增、停用/启用、删除、解锁、重置密码；Account 模型对未来新类型开放。

**Independent Test**: 同一 User 两个 Account 都能独立登录并解析为同一 User；停用其一不影响另一个（quickstart 场景 7、8、9）。

### Tests for User Story 3 ⚠️

- [X] T092 [P] [US3] 创建 `BT/identity/AccountServiceTest.java`：新增 Account（用户名规则同上，规范化 NFKC + 小写，冲突 → `USERNAME_TAKEN`）；删除/停用最后一个可用 Account 无 `confirm=true` → `LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED`；停用/删除 Account 时撤销其全部会话；重置密码：写新哈希、`must_change_password=true`、撤销该 Account 全部会话、系统生成临时密码仅返回一次；解锁清零 `failed_attempts` 与 `locked_until`；全部操作写审计（`ACCOUNT_CREATED/DISABLED/ENABLED/DELETED`、`PASSWORD_RESET`、`ACCOUNT_UNLOCKED`）
- [X] T093 [P] [US3] 创建 `BT/api/AccountsApiContractIT.java`：`/users/{userId}/accounts` 及其 `disable|enable|unlock|reset-password`、`DELETE` 响应通过契约校验；`confirm` 参数行为；`MEMBER` → 403
- [X] T094 [P] [US3] 创建 `BT/auth/MultiAccountLoginIT.java`：同一 User 两个 Account 分别登录均解析为同一 `userId`；停用其一仅该 Account 无法登录且其会话已被撤销、下一次请求 401，另一个与 User 不受影响（SC-004）；新增一个测试用 `Authenticator` 实现无需改动 `app_user` 即可注册（验证 SC-007 的扩展点）
- [X] T095 [P] [US4] 在 `BT/isolation/TenantIsolationIT.java` 增加 Account 端点用例：A 的 JWT 对 B 的 Account 执行读取、新增、停用、启用、解锁、重置密码、删除全部 404
- [ ] T096 [P] [US3] 创建 `FT/component/AccountsSection.test.tsx`：表格列「类型、登录标识、状态、最近登录、操作」；被锁定显示「已锁定至 HH:mm」胶囊；操作含停用·启用、解锁、重置密码、删除；删除/停用最后一个可用 Account 弹确认框（提交带 `confirm=true`）；新增 Account 与重置密码成功后展示一次性临时密码框

### Implementation for User Story 3

- [X] T097 [US3] 创建 `B/identity/AccountService.java`（依赖 T019、T025、T027；使 T092 通过）
- [X] T098 [US3] 创建 `B/api/AccountController.java` 与 DTO（`Account`、`AccountWithTemporaryPassword`、`CreateAccountRequest`、`ResetPasswordRequest`）（使 T093、T094、T095 通过）
- [ ] T099 [US3] 创建 `F/features/accounts/AccountsSection.tsx`、`ConfirmDialog.tsx`，并接入 `F/app/(console)/users/[id]/page.tsx`（使 T096 通过）
- [ ] T100 [US3] 创建 E2E `FT/e2e/accounts-journey.spec.ts`：为同一用户新增第二个 Account、分别登录、停用其一、最后一个 Account 的确认流程、重置密码后原会话失效并强制改密（quickstart 场景 7、8、9、14）

**Checkpoint**: 全部 User Story 可独立工作

---

## Phase 7: User Story 5 - 在「会话管理」页查看并强制下线登录会话 (Priority: P2)

**Goal**: 管理员在独立的「会话管理」页（`/sessions`）查看本租户全部有效登录会话，并可强制下线单个会话或某用户的全部会话；会话与授权、隔离、审计都在后端（research R4、R18），BFF 纯代理。**不放在用户详情中**。

**Independent Test**: 同一 User 在两个浏览器各登录一次，管理员在会话管理页强制下线其一，该浏览器下一次请求即 401，另一个不受影响（quickstart 场景 19；SC-008）。

### Tests for User Story 5 ⚠️

- [ ] T101 [P] [US5] 创建 `BT/api/SessionsApiIT.java`：`GET /sessions`（仅 `TENANT_ADMIN`）只返回本租户有效会话，按用户关键字（显示名/登录标识）过滤、按最近活跃倒序分页，`current` 依据 JWT 的 `sid`，响应不含令牌、令牌哈希、原始 User-Agent；`DELETE /sessions/{sessionId}`：目标是当前会话 → 409 `CANNOT_REVOKE_CURRENT_SESSION`，否则撤销（`ADMIN_REVOKED`）并写审计 `SESSION_REVOKED`，被撤销会话的下一次请求 → 401；不存在/已过期/其他租户 → 204；`DELETE /users/{userId}/sessions`：目标为调用者本人 → 409 `CANNOT_REVOKE_OWN_SESSIONS`，其他租户或不存在的用户 → 404，否则撤销全部并写审计 `SESSIONS_REVOKED_ALL`（含数量）；`MEMBER` → 403；响应通过契约校验
- [ ] T102 [P] [US4] 在 `BT/isolation/TenantIsolationIT.java` 增加会话端点用例：租户 0 的管理员 `GET /sessions` 看不到租户 1 的会话；对租户 1 的会话 id 调用 `DELETE /sessions/{id}` 返回 204 但该会话仍然有效；对租户 1 的用户 `DELETE /users/{id}/sessions` → 404
- [ ] T103 [P] [US5] 创建 `BT/api/MeRoleFreshnessIT.java`：`/auth/me` 的 `role` 取自数据库当前值——管理员被降级后，即使 JWT 声明仍是 `TENANT_ADMIN`，下一次调用也返回 `MEMBER`（BFF 据此拒绝会话管理操作）
- [ ] T104 [P] [US5] 创建 `BT/auth/SessionRevocationIT.java`：User 停用、Account 停用、Account 删除、重置密码、修改密码（保留当前会话）、登出，各自在同一事务内撤销相应会话并写入正确的 `revoke_reason`；被撤销会话的 JWT 下一次请求 → 401；撤销与审计要么同时成功要么同时回滚

### Implementation for User Story 5

- [ ] T105 [US5] 创建 `B/auth/SessionAdminService.java` 与 `B/api/SessionController.java`：列表、强制下线单个、下线某用户全部；撤销与审计同事务；`current` 依据 `sid`；`AuditAction` 含 `SESSION_REVOKED`、`SESSIONS_REVOKED_ALL`（依赖 T027；使 T101、T102、T104 通过）
- [ ] T106 [US5] 在 `B/api/AuthController.java` 的 `/auth/me` 中 `role` 改为读取数据库当前值（使 T103 通过）

### Tests for User Story 5（前端页面）⚠️

- [ ] T107 [P] [US5] 创建 `FT/component/SessionsPage.test.tsx`：筛选栏（用户关键字）；表格列「用户、账号、设备、IP、登录时间、最近活跃、到期时间、操作」；分页；当前会话行显示「当前」且无操作；「强制下线」与「下线该用户全部会话」均弹确认对话框，管理员自己的用户行不显示后者；空态「当前没有有效的登录会话」；`CANNOT_REVOKE_*` 错误按 `code` 提示

### Implementation for User Story 5（前端页面）

- [ ] T108 [US5] 创建 `F/app/(console)/sessions/page.tsx` 与 `F/features/sessions/SessionsTable.tsx`（复用 `ConfirmDialog`、table、pagination），并在 `F/components/AppShell.tsx` 侧边栏加入「会话管理」入口（使 T107 通过）；**不在用户详情页中放会话区**
- [ ] T109 [US5] 创建 E2E `FT/e2e/sessions-journey.spec.ts`：同一用户在两个浏览器上下文登录；管理员在会话管理页看到会话；强制下线其一后该上下文下一次请求回到登录页、另一个仍可用；「下线该用户全部会话」后均失效；自己的当前会话不可下线（quickstart 场景 19）

**Checkpoint**: 会话管理可独立工作

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 审计查询、并发/失效/脱敏/性能验证、门禁与归档

- [ ] T110 [P] 创建 `BT/api/AuditLogsApiIT.java`：`GET /audit-logs`（`action`、`actorUserId`、`from`、`to`、`page`、`size`）仅返回本租户记录；`MEMBER` → 403；响应通过契约校验；quickstart 场景 16 的各事件均可查到，字段含操作者、对象、时间、结果、IP（来自 JWT `cip`）
- [ ] T111 实现 `B/audit/AuditQueryService.java` 与 `B/api/AuditLogController.java`（使 T110 通过）
- [ ] T112 [P] 创建 `BT/identity/ConcurrencyIT.java`：两个管理员同时编辑同一用户，后提交者 409 `VERSION_CONFLICT`；并发停用最后两名管理员不会使租户失去全部管理员（quickstart 场景 11、15）
- [ ] T113 [P] 创建 `FT/unit/session-expiry.test.ts`：resolve 缓存 TTL 到期后重新向后端确认（TTL 为 0 时每次都确认）；缓存期内会话已被后端撤销或过期时，下一次 API 调用被后端 401 拒绝，BFF 清除 Cookie 并让页面跳回 `/login?next=<原目标页>`（quickstart 场景 14；401 后清除 Cookie 的基础行为见 T048）
- [ ] T114 [P] 创建 `BT/security/LogRedactionIT.java` 与 `FT/unit/log-redaction.test.ts`：登录、改密、创建用户、重置密码流程后，捕获的全部日志与审计 `detail` 不含任何密码、会话令牌或 JWT（FR-006）
- [ ] T115 [P] 创建 `BT/performance/ListPerformanceIT.java`（带 `@Tag("performance")`，默认不在 CI 主流程运行）：租户内 1,000 用户，100 并发会话下列表与搜索 p95 ≤ 2 秒（SC-005、quickstart 场景 17）
- [ ] T116 后端门禁：运行 `verify`，确保 Spotless 与 JaCoCo 行覆盖率 ≥ 90% 通过；补齐缺口测试（排除路径仅 `*Application`）
- [ ] T117 前端门禁：运行 lint、typecheck、`test:coverage`（行覆盖率 ≥ 90%）、build；补齐缺口测试
- [ ] T118 更新 `backend/AGENTS.md`、`frontend/AGENTS.md` 为最终命令、环境变量、覆盖率与 CI 说明
- [ ] T119 按 `quickstart.md` 逐条验证场景 1–19，记录结果；场景 12（跨租户隔离）、18（内部凭证校验）与 19（会话管理）必须 100% 通过
- [ ] T120 安全复核：Cookie 属性、BFF 来源校验、限流、枚举防护、JWT 校验（算法白名单、过期、受众）、会话撤销与失效（`session` 表）、日志脱敏、RLS 角色权限、后端仅内网可达；结论记入 PR 说明
- [ ] T121 归档（根 AGENTS.md「Project Context」）：把已落地的需求、设计沉淀到 `docs/achieve-specs/tenant-admin-center.md`，架构沉淀到 `docs/system-design/tenant-admin-center.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup，**阻塞**所有 User Story
- **US1 (Phase 3, P1)**: 依赖 Foundational；MVP
- **US4 (Phase 4, P1)**: 依赖 Foundational 与 US1（需要登录与 JWT 鉴权；用户/Account/会话事件端点的隔离用例在 US2/US3/US5 阶段补充）
- **US2 (Phase 5, P2)**: 依赖 US1（登录、JWT 鉴权）与 US4（隔离基线）
- **US3 (Phase 6, P2)**: 依赖 US2（用户存在）
- **US5 (Phase 7, P2)**: 依赖 US1（后端会话机制与 `/auth/me`）；可与 US2 并行
- **Polish (Phase 8)**: 依赖全部目标 User Story

### Within Each Phase

- 测试任务先于实现任务，且必须先失败
- 后端：实体/仓储 → 服务 → 控制器；BFF：server 基础设施 → 路由处理器；前端：ui 组件 → 功能组件 → 页面 → E2E
- 契约变更须先改 `contracts/openapi.yaml` 与失败用例，再改实现与 `schema.d.ts`

### Parallel Opportunities

- Setup：T002–T007、T009 可并行
- Foundational：后端测试 T010–T012、T016、T018、T021、T024、T028、T030，前端测试 T032、T034、T036，BFF 测试 T038、T040、T042、T044、T048、T046 互不同文件，可并行
- 后端、BFF/前端两条线可由不同人并行推进，仅在联调与 E2E 处汇合；BFF 单测以 `ioredis-mock` 与桩后端运行，不依赖真实后端
- US2 与 US3 的前端 ui 组件任务（T087）可与对应后端任务并行

---

## Parallel Example: User Story 1

```text
# 测试一起开：
后端：T050 LoginServiceTest · T051 AuthApiContractIT · T052 BootstrapRunnerIT
BFF：T059 login-route · T060 logout-route
页面：T063 LoginPage · T064 ChangePasswordPage · T065 AppShell/middleware · T066 ForbiddenGuard

# 实现分三条线：
后端 T053 → T054/T055 → T056（T057、T058 可并行）
BFF  T061 → （T062 可并行）
页面 T067/T068 并行 → T069 → T070/T071 → T072
```

---

## Implementation Strategy

### MVP First（仅 US1）

1. Phase 1 Setup → Phase 2 Foundational
2. Phase 3 US1，用 `bootstrap` / dev seed 预置数据，验证 quickstart 场景 1–4
3. **STOP and VALIDATE** 后再继续

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. US1 → 可登录、进入用户列表（MVP）
3. US4 → 隔离基线与验证套件（P1 安全底线，紧随 MVP）
4. US2 → 用户管理
5. US3 → 多 Account 管理（在用户详情页内）
6. US5 → 独立的「会话管理」页
7. Polish → 审计查询、并发/失效/脱敏/性能验证、门禁与归档

### Notes

- 每个任务或逻辑组完成后提交（Conventional Commits，说明「为何」）
- 覆盖率门禁、Spotless、lint、typecheck 为合并前必过项
- 若实现中出现需求或设计变更：先更新 `spec.md`、`plan.md`、`tasks.md`，经用户确认后再改代码（根 AGENTS.md「需求与设计变更管理」）
