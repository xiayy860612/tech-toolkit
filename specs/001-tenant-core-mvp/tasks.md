---

description: "Task list for Tenant Core MVP"
---

# Tasks: Tenant Core MVP

**Input**: Design documents from `/specs/001-tenant-core-mvp/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/（cli.md、tenant-core-api.yaml）

**Tests**: **必须**。根 `AGENTS.md`「测试先行」要求 red → green → refactor：每个故事先写会失败的测试并确认失败，再写实现；行覆盖率 ≥ 90%。

**Organization**: 按用户故事分组。US1（CLI Bootstrap）→ US2（登录与 Token）→ US3（导航与列表）。US2 后端的集成测试用 US1 的服务（Tenant、User、Account、订阅）造数据，因此 US2 后端依赖 US1 的服务任务（T026–T030）；US2 前端任务可与 US1 并行。US3 依赖 US2 的认证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1 / US2 / US3
- 路径简写（仅为本文件可读性；执行时使用完整路径）：
  - `CORE` = `backend/core-lib/src/main/java/tech/toolkit/tenantcore`
  - `CORE_T` = `backend/core-lib/src/test/java/tech/toolkit/tenantcore`
  - `API` = `backend/tenant-core-api/src/main/java/tech/toolkit/tenantcore/api`
  - `API_T` = `backend/tenant-core-api/src/test/java/tech/toolkit/tenantcore/api`
  - `CLI` = `backend/tenant-core-cli/src/main/java/tech/toolkit/tenantcore/cli`
  - `CLI_T` = `backend/tenant-core-cli/src/test/java/tech/toolkit/tenantcore/cli`
  - `WEB` = `apps/platform-web`
- 数据库：单一 schema `public`，表名前缀 `core_`、`uam_`、`audit_`；package 间不建外键、不联表（见 data-model.md）。

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 工程骨架、子项目文档、本地依赖，以及实现前的设计文档回写

- [x] T001 创建 Maven 父工程 `backend/pom.xml`，子项目 `core-lib`、`tenant-core-api`、`tenant-core-cli`；Java 21、Spring Boot 3.x BOM；配置 JaCoCo 行覆盖率 ≥ 90% 的 `check` 目标（排除启动类与生成代码），及三个子项目各自的 `pom.xml`
- [x] T002 [P] 编写 `backend/AGENTS.md` 与 `backend/CLAUDE.md`（内容 `@AGENTS.md`）：技术栈、目录、dev/lint/typecheck/test/build 命令、JaCoCo 口径（行覆盖率）与排除路径、环境变量与 `.env.example` 说明、命名规则（沿用 spec 的术语表与 plan 的命名映射，代码使用同一英文业务术语）
- [x] T003 [P] 初始化 `apps/platform-web`：`package.json`（Next.js 15、React 19、TypeScript 5、ioredis、Vitest + React Testing Library、Playwright）、`tsconfig.json`、`next.config.ts`、`vitest.config.ts`（行覆盖率阈值 90%）、`playwright.config.ts`
- [x] T004 [P] 编写 `apps/platform-web/AGENTS.md` 与 `apps/platform-web/CLAUDE.md`（内容 `@AGENTS.md`）：技术栈、目录、dev/lint/typecheck/test/build 命令、覆盖率口径与排除路径、环境变量、命名规则（沿用 spec 的术语表与 plan 的命名映射，组件、类型、路由、测试名使用同一英文业务术语）
- [x] T005 [P] 创建本地依赖 `deploy/docker-compose.yml`（PostgreSQL 16、Redis 7）、`deploy/initdb/01-roles.sql`（创建应用角色 `tenant_core_app`）及 `backend/.env.example`、`apps/platform-web/.env.example`（数据库连接、Redis 地址、`TENANT_CORE_API_URL`、`UAM_ISSUER`、`UAM_AUDIENCE`、`UAM_CELL_ID`、`UAM_SIGNING_KEY_PEM` 占位、`UAM_JWT_TTL_SECONDS`、session 密钥占位；不含真实秘密）
- [x] T006 [P] 配置静态检查：后端 Spotless/Checkstyle（`backend/pom.xml` 插件配置）、前端 ESLint + Prettier（`apps/platform-web/eslint.config.mjs`、`.prettierrc`）；根 `.gitignore` 补充 `target/`、`node_modules/`、`.env`、`.next/`、`*.pem`
- [x] T007 回写设计文档（**实现前完成**，已确认下列 4 项调整，2026-11-07）：更新 `docs/system-design/02-authentication.md`、`04-multi-tenancy.md`、`06-decisions.md`、`README.md`，标注“MVP 调整，落地后定稿”：① 不部署网关，UAM 同时校验 Opaque Token 与 Internal JWT 并提供换发与 JWKS；② 只分 Java 包与表名前缀，不分 schema、不拆 package 角色，拆服务时按前缀迁表；③ 暂无 tenant_user 档案表，User 列表读 `uam_user`；④ CLI 直接写 Tenant 注册表与订阅；并写入“User / Account（account）”术语与关系

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有用户故事共用的库、数据库迁移、密码与 Audit 能力

**⚠️ CRITICAL**: 本阶段完成前不开始任何用户故事

- [x] T008 编写集成测试基类 `CORE_T/support/PostgresTestBase.java`（Testcontainers PostgreSQL 16，执行 `deploy/initdb/01-roles.sql` 同款初始化，运行 Flyway 迁移，每个测试类清库）
- [x] T009 先写失败的迁移约束测试 `CORE_T/migration/SchemaConstraintsIT.java`：断言 `core_tenant.slug` 唯一且符合 `^[a-z][a-z0-9-]{2,31}$`、`status ∈ (ACTIVE, SUSPENDED)`；`core_module_subscription.module_code` CHECK ∈ (`platform`)、(tenant_id, module_code) 唯一；`uam_user.status ∈ (ACTIVE, DISABLED)`；`uam_account` 唯一 (tenant_id, type, provider, subject)、`subject` 符合 `^[a-z0-9][a-z0-9._-]{2,63}$`、每 User 至多一个 `PASSWORD` Account；`uam_opaque_token.sid` 唯一；各表之间无跨 package 外键；`audit_event` 对 `tenant_core_app` 无 UPDATE/DELETE/TRUNCATE 权限
- [x] T010 编写 Flyway 迁移 `backend/core-lib/src/main/resources/db/migration/V1__core_tables.sql`：`core_tenant`（id uuid PK, slug text 唯一且 CHECK (slug ~ '^[a-z][a-z0-9-]{2,31}$'), name text 非空 CHECK (char_length(name) BETWEEN 1 AND 100), status text 默认 ACTIVE CHECK (status IN ('ACTIVE','SUSPENDED')), created_at timestamptz 非空）、`core_module_subscription`（id, tenant_id FK → core_tenant, module_code CHECK ∈ ('platform'), status 'SUBSCRIBED', subscribed_at；唯一 (tenant_id, module_code)）
- [x] T011 编写 Flyway 迁移 `backend/core-lib/src/main/resources/db/migration/V2__uam_tables.sql`：`uam_tenant_login_method`（PK (tenant_id, method_type), method_type CHECK ∈ ('PASSWORD'), enabled_at；tenant_id 无外键）、`uam_user`（id, tenant_user_id 唯一 `tu_…`, tenant_id 无外键, display_name CHECK (char_length(display_name) BETWEEN 1 AND 100), status 默认 ACTIVE CHECK (status IN ('ACTIVE','DISABLED')), created_at）、`uam_account`（id, user_id FK → uam_user 非空, tenant_id, type 'PASSWORD', provider 空串, subject text CHECK (subject ~ '^[a-z0-9][a-z0-9._-]{2,63}$')（正则已限定小写）, created_at；唯一 (tenant_id, type, provider, subject)；每 User 一个 PASSWORD 的部分唯一索引）、`uam_credential_password`（account_id PK/FK → uam_account, password_hash, must_change_password boolean, updated_at, failed_count int 默认 0, locked_until 可空）、`uam_opaque_token`（token_hash bytea PK, sid text 唯一 `s_…`, account_id FK, user_id FK, tenant_id, created_at, last_seen_at, expires_at, revoked_at 可空）
- [x] T012 编写 Flyway 迁移 `backend/core-lib/src/main/resources/db/migration/V3__audit_table.sql`：`audit_event`（id uuid PK, occurred_at, actor, action, tenant_id 可空无外键, target, outcome ∈ (SUCCESS, FAILURE, NOOP), trace_id 可空）；从 `tenant_core_app` 撤销该表的 UPDATE、DELETE、TRUNCATE；使 T009 转绿
- [x] T013 [P] 先写失败的单元测试 `CORE_T/common/IdGeneratorTest.java`、`CORE_T/common/ErrorCodeTest.java`，再实现 `CORE/common/IdGenerator.java`（生成 `tu_`、`s_` 前缀的短随机串）、`CORE/common/DomainException.java` 与 `CORE/common/ErrorCode.java`（INVALID_CREDENTIALS、MODULE_NOT_SUBSCRIBED、UNAUTHORIZED、PASSWORD_CHANGE_REQUIRED、PASSWORD_POLICY、VALIDATION、NOT_FOUND、CONFLICT）、`CORE/common/ClockProvider.java`
- [x] T014 [P] 先写失败的单元测试 `CORE_T/uam/PasswordPolicyTest.java`（12–128 字符；不得等于 username；边界 11/12/128/129）与 `CORE_T/uam/PasswordHasherTest.java`（Argon2id 编码串、同密码不同盐、校验通过/失败、哈希不含明文），再实现 `CORE/uam/PasswordPolicy.java`、`CORE/uam/PasswordHasher.java`（Spring Security `Argon2PasswordEncoder`，参数 m=19MiB, t=2, p=1，可配置）
- [x] T015 [P] 先写失败的集成测试 `CORE_T/audit/AuditRecorderIT.java`（写入 SUCCESS/FAILURE/NOOP；拒绝含密码/哈希/Token 样式的 target），再实现 `CORE/audit/AuditEvent.java`、`CORE/audit/AuditRecorder.java`（JdbcClient 只追加）
- [x] T016 [P] 实现 `CORE/common/DatabaseConfig.java`（DataSource、JdbcClient、Flyway 启动，读取环境变量，无硬编码连接串；迁移用 owner 连接，运行用 `tenant_core_app` 连接）与分页对象 `CORE/common/Page.java`

**Checkpoint**: 迁移可在空库上执行，密码策略、哈希、Audit、ID 生成均有通过的测试

---

## Phase 3: User Story 1 - 运维通过 CLI Bootstrap Tenant (Priority: P1) 🎯 MVP

**Goal**: 通过 CLI 依次完成建 Tenant、启用 username/password 登录、建 User、建 Account、订阅 `platform`，并可用只读命令核对；全部幂等并入 Audit。

**Independent Test**: 空库执行 [quickstart.md](quickstart.md) 场景 A，`tenant show` 可见 Tenant、Login Method、User 及 Account、订阅；每条命令重复执行结果不变；输出/日志/Audit 中检索初始密码为 0 命中。

### Tests for User Story 1（先写，确认失败）⚠️

- [x] T017 [P] [US1] 服务层集成测试 `CORE_T/tenant/TenantServiceIT.java`：创建、同 slug 同 name 返回 exists、同 slug 异 name 抛 CONFLICT、slug 非法（`^[a-z][a-z0-9-]{2,31}$`）拒绝、Audit Event `tenant.create`
- [x] T018 [P] [US1] 服务层集成测试 `CORE_T/uam/LoginMethodServiceIT.java`：启用、重复启用为 already_enabled、Tenant 不存在抛 NOT_FOUND、类型非 password 拒绝
- [x] T019 [P] [US1] 服务层集成测试 `CORE_T/uam/UserServiceIT.java`：创建 User（Display Name 必填 ≤ 100）、同 Tenant 同 Display Name 返回已有 `tenantUserId`、Tenant 不存在拒绝、`tenant_user_id` 以 `tu_` 开头
- [x] T020 [P] [US1] 服务层集成测试 `CORE_T/uam/AccountServiceIT.java`：创建 Account 并设 `must_change_password=true`、username 转小写并校验 `^[a-z0-9][a-z0-9._-]{2,63}$`、User 属于其他 Tenant 拒绝、未启用密码登录拒绝、username 被同 Tenant 其他 User 占用抛 CONFLICT、同 User 重复创建为 exists 且不改密码、弱密码拒绝、数据库中不存在明文密码
- [x] T021 [P] [US1] 服务层集成测试 `CORE_T/tenant/SubscriptionServiceIT.java`：订阅 `platform`、重复订阅为 already_subscribed、未知 Module 拒绝、Tenant 不存在拒绝
- [x] T022 [P] [US1] 服务层集成测试 `CORE_T/tenant/TenantDetailIT.java`（FR-022）：`show` 返回 Tenant、已启用 Login Method、User（Display Name + 关联 username）、订阅，且不含任何 Credential 字段
- [x] T023 [P] [US1] CLI 契约测试 `CLI_T/CliContractIT.java`（进程内 picocli + Testcontainers）：退出码 0/1/2/3 对应 [contracts/cli.md](contracts/cli.md)；每个写命令重复执行第二次退出码 0 且行数不变；传入 `--password` 报未知选项；`--output json` 的结果值；前置条件缺失时无部分写入；Audit actor 形如 `cli:<os-user>@<host>`
- [x] T024 [P] [US1] CLI 保密测试 `CLI_T/PasswordLeakIT.java`：以 `--password-stdin` 提供测试口令，对 stdout、stderr、日志输出与 `audit_event` 全表检索口令与哈希，命中数为 0（SC-005）
- [x] T025 [P] [US1] 单元测试 `CLI_T/PasswordReaderTest.java`：TTY 隐藏输入二次确认、两次不一致失败、`--password-stdin` 读一行、空输入失败

### Implementation for User Story 1

- [x] T026 [P] [US1] 实现 `tenant` package：`CORE/tenant/Tenant.java`、`CORE/tenant/TenantRepository.java`、`CORE/tenant/TenantService.java`（slug 校验、幂等、Audit `tenant.create`）
- [x] T027 [P] [US1] 实现 Login Method：`CORE/uam/LoginMethodRepository.java`、`CORE/uam/LoginMethodService.java`（Audit `login-method.enable`；Tenant 存在性经 `TenantService` 接口校验，不联表）
- [x] T028 [P] [US1] 实现 User：`CORE/uam/User.java`、`CORE/uam/UserRepository.java`、`CORE/uam/UserService.java`（按 (Tenant, Display Name) 幂等，使用 `IdGenerator`，Audit `user.create`）
- [x] T029 [US1] 实现 Account：`CORE/uam/Account.java`、`CORE/uam/AccountRepository.java`、`CORE/uam/PasswordCredential.java`、`CORE/uam/PasswordCredentialRepository.java`、`CORE/uam/AccountService.java`（依赖 T014、T027、T028；`Authenticator` 接口 `CORE/uam/Authenticator.java` 仅声明，密码实现留给 US2；Audit `account.create`，不记录密码）
- [x] T030 [P] [US1] 实现订阅：`CORE/tenant/ModuleSubscription.java`、`CORE/tenant/SubscriptionRepository.java`、`CORE/tenant/SubscriptionService.java`（Audit `subscription.add`）
- [x] T031 [P] [US1] 实现 `CORE/tenant/TenantDetailService.java`（供 `tenant show`；User 与 Account 部分经 `uam` package 的接口取回，服务层组装，不联表）
- [x] T032 [P] [US1] 实现 `CLI/PasswordReader.java`（Console 隐藏输入 + 确认；`--password-stdin`）
- [x] T033 [US1] 实现 CLI 入口与命令：`CLI/TenantCoreCli.java`、`CLI/commands/TenantCreateCommand.java`、`LoginMethodEnableCommand.java`、`UserCreateCommand.java`、`AccountCreateCommand.java`、`SubscriptionAddCommand.java`、`TenantShowCommand.java`；统一退出码映射 `CLI/ExitCodes.java`、`--output json` 输出 `CLI/OutputFormatter.java`、Audit actor `cli:<os-user>@<host>`；使 T017–T025 转绿
- [x] T034 [US1] 为 CLI 增加日志脱敏与错误可读性：`backend/tenant-core-cli/src/main/resources/logback.xml`（屏蔽消息中的 password/token/secret 值；日志级别 WARN）；`TenantCoreCli` 的参数错误处理器不回显未知参数（防止 `--password <值>` 被打印）；并复跑 T024
- [x] T035 [US1] 打包 CLI：在 `backend/tenant-core-cli/pom.xml` 配置可执行 jar；新增启动脚本 `backend/tenant-core-cli/bin/tenant-core-cli`（读取环境变量，转发全部参数）；在 `backend/AGENTS.md` 写明调用方式；`CLI_T/PackagedCliSmokeIT.java` 对打包产物执行 `tenant create` 作冒烟

**Checkpoint**: US1 独立可用：quickstart 场景 A 全部通过，CLI 与服务层覆盖率 ≥ 90%

---

## Phase 4: User Story 2 - User 在 platform web 登录，UAM 同时校验两类 Token (Priority: P1)

**Goal**: Tenant Slug + username + 密码登录；首次登录强制改密；统一失败提示与锁定；Session 与退出；UAM 同时支持 Opaque Token 与 Internal JWT（换发、JWKS、验签）。

**Independent Test**: 用 CLI（或测试里经 US1 服务）创建的 Account，在浏览器完成登录 → 改密 → 进入主界面；错误 username 或密码提示一致；5 次失败后锁定；退出后无法访问受保护页（场景 B）；Internal JWT 换发与验签通过（场景 D）。

### Tests for User Story 2（先写，确认失败）⚠️

> 后端集成测试用 US1 的服务（T026–T030）造数据，故依赖这些任务。

- [x] T036 [P] [US2] 单元测试 `CORE_T/uam/OpaqueTokenServiceTest.java`：Token 前缀 `tk_`、仅存 SHA-256 哈希、`sid` 以 `s_` 开头且唯一、空闲 30 分钟滑动过期、绝对上限 8 小时、吊销后失效
- [x] T037 [P] [US2] 集成测试 `CORE_T/uam/PasswordAuthenticatorIT.java`：成功；密码错误；Account 不存在（断言哑哈希校验被调用，另加宽松耗时上限，不做严格时序断言）；Tenant `SUSPENDED`、User `DISABLED`、`PASSWORD` 未启用均统一为 INVALID_CREDENTIALS；**未订阅 `platform` 的 Tenant：错误密码 → INVALID_CREDENTIALS，正确密码 → MODULE_NOT_SUBSCRIBED**；连续 5 次失败后 15 分钟内即使密码正确仍 INVALID_CREDENTIALS 且文案含“请稍后再试”；成功登录清零失败计数
- [x] T038 [P] [US2] 集成测试 `CORE_T/uam/ChangePasswordIT.java`：旧密码错误拒绝；新密码不合规返回 PASSWORD_POLICY；成功后清除 `must_change_password`、吊销旧 Token 并签发新 Token
- [x] T039 [P] [US2] 单元测试 `CORE_T/uam/InternalJwtServiceTest.java` 与 `CORE_T/uam/PrincipalResolverTest.java`（FR-023）：签发的声明符合 data-model「Internal JWT」（`iss`、`aud`、`sub=tenant_user_id`、`exp-iat=120`、`iat`、`jti`、`tenant_id`、`cell_id`、`client_type=web`、`azp=platform-web`、`sid`，无 `scope`）；ES256 验签通过；拒绝错误 `iss`、错误 `aud`、过期、签名被篡改、`alg=none`、HS256、未知 `kid`；JWKS 只含公钥与 `kid`；非 dev profile 缺少 `UAM_SIGNING_KEY_PEM` 启动失败；`PrincipalResolverTest`：仅 `X-Session-Id` → 按 Opaque Token 解析；仅 `Authorization: Bearer` 且 JWT 合法 → 按 Internal JWT 解析；两个头并存、都缺失、Bearer 里放 Opaque Token、`X-Session-Id` 里放 JWT、`Authorization` 不是 Bearer 方案 → 均抛 UNAUTHORIZED
- [x] T040 [P] [US2] 集成测试 `CORE_T/uam/TokenExchangeIT.java`（FR-023）：有效 Opaque Token 可换发；已吊销/过期/要求改密的 Session 换发失败；JWT 的 `sub`、`tenant_id`、`sid` 与 Session 一致；写 `token.exchange` Audit；退出后不能再换发，已换发的 JWT 在 `exp` 前仍通过验签
- [x] T041 [P] [US2] API 契约测试 `API_T/AuthApiContractIT.java`（对照 [contracts/tenant-core-api.yaml](contracts/tenant-core-api.yaml) 校验 `/v1/auth/login`、`/logout`、`/change-password`、`/me`、`/exchange`、`/.well-known/jwks.json` 的状态码与响应体）；`must_change_password=true` 时除 change-password/logout/me 外所有接口返回 403 PASSWORD_CHANGE_REQUIRED；`/me` 与列表类接口两种请求凭据都接受；login、logout、change-password、exchange 带 JWT 时被拒绝；同时带 `X-Session-Id` 与 `Authorization: Bearer` → 401；Bearer 里放 Opaque Token → 401；`X-Session-Id` 里放 Internal JWT → 401；用 Internal JWT 调 `/me` 返回 `accountUsername`、`mustChangePassword`，对应 Opaque Token 已吊销时到 JWT 过期前仍可返回
- [x] T042 [P] [US2] API 集成测试 `API_T/AuthAuditIT.java`：`login.success`、`login.failure`、`password.change`、`logout`、`token.exchange` 均入 Audit，且不含密码/Token
- [x] T043 [P] [US2] 前端单元测试 `WEB/tests/unit/session.test.ts`（Redis session 存取、TTL、销毁，使用 ioredis-mock）与 `WEB/tests/unit/tenantCoreClient.test.ts`（以请求头 `X-Session-Id` 携带 Opaque Token、错误码映射、Token 不出现在返回给页面的数据中；后端返回 401 时抛出 UnauthorizedError）
- [x] T044 [P] [US2] 前端组件测试 `WEB/tests/component/LoginForm.test.tsx`（三个字段 `tenantSlug`、`username`、`password`、统一错误提示、无访问权限提示、无障碍标签、提交禁用态）与 `WEB/tests/component/ChangePasswordForm.test.tsx`（两次输入一致、策略提示）
- [x] T045 [P] [US2] 前端中间件测试 `WEB/tests/unit/middleware.test.ts`：未登录访问受保护路径重定向 `/login?next=…`；`mustChangePassword` 时重定向 `/change-password`；已登录访问 `/login` 跳主页；session 存在但后端判 Token 失效（401）时清除 session 并重定向登录页

### Implementation for User Story 2

- [x] T046 [P] [US2] 实现 Opaque Token：`CORE/uam/OpaqueToken.java`、`CORE/uam/OpaqueTokenRepository.java`、`CORE/uam/OpaqueTokenService.java`
- [x] T047 [US2] 实现 `CORE/uam/PasswordAuthenticator.java`（实现 `Authenticator`；Tenant Slug + username 解析 Account → User；准入分两步，先 username 与密码、后订阅，见 [data-model.md](data-model.md)「状态与规则」；哑哈希时序一致）与 `CORE/uam/LoginAttemptPolicy.java`（5 次 / 15 分钟，可配置）
- [x] T048 [P] [US2] 实现 Internal JWT：`CORE/uam/SigningKeyProvider.java`（读取 `UAM_SIGNING_KEY_PEM`；`dev` profile 可生成临时密钥，其他 profile 缺失则启动失败；`kid` 取公钥指纹）、`CORE/uam/InternalJwtService.java`（ES256 签发与验签，配置项 `UAM_ISSUER`、`UAM_AUDIENCE`、`UAM_CELL_ID`、`UAM_JWT_TTL_SECONDS`；只接受配置的 `iss` 与 `aud`）、`CORE/uam/JwksPublisher.java`
- [x] T049 [US2] 实现统一入口 `CORE/uam/PrincipalResolver.java` 与 `CORE/uam/AuthenticatedPrincipal.java`：请求头决定校验方式——`X-Session-Id` 走 Opaque Token 查表并滑动续期，`Authorization: Bearer` 按 Internal JWT 本地验签；两个头并存、都缺失或放错位置抛 UNAUTHORIZED；输出相同的主体（`tenant_user_id`、`tenant_id`、来源 OPAQUE/JWT、`sid`）；使 T039 的 `PrincipalResolverTest` 转绿
- [x] T050 [US2] 实现 `CORE/uam/AuthService.java`（login、logout、changePassword、exchange、me；exchange 仅接受有效且未要求改密的 Opaque Token；`me` 对 Internal JWT 经 `sid` 找到对应的 `uam_opaque_token` 记录取 username，`mustChangePassword` 读 Credential 当前值，令牌已吊销时仍可读，只用于展示、不放行；Audit）；依赖 T046–T049
- [x] T051 [P] [US2] 创建 API 应用 `backend/tenant-core-api`：`API/TenantCoreApiApplication.java`、`application.yml`（环境变量取值）、`API/web/ApiExceptionHandler.java`（`ErrorCode` → HTTP 状态与 `{code,message}`）
- [x] T052 [US2] 实现 `API/auth/RequestAuthenticationFilter.java`（使用 `PrincipalResolver`；`must_change_password` 时仅放行 change-password/logout/me；login/logout/change-password/exchange 只接受 Opaque Token）、`API/auth/AuthController.java`（`/v1/auth/login|logout|change-password|me`；`/me` 对两种请求凭据返回相同结构）、`API/auth/TokenExchangeController.java`（`/v1/auth/exchange`）、`API/auth/JwksController.java`（`/.well-known/jwks.json`，仅内网暴露）；DTO 与 OpenAPI 一致；使 T036–T042 转绿
- [x] T053 [P] [US2] 实现前端服务端基础：`WEB/src/server/session.ts`（Redis session，HttpOnly + Secure + SameSite=Lax cookie，TTL 对齐 Token）、`WEB/src/server/tenantCoreClient.ts`（调用 `TENANT_CORE_API_URL`，401 抛 UnauthorizedError）、`WEB/src/server/env.ts`
- [x] T054 [US2] 实现 `WEB/src/middleware.ts`（受保护路由、改密强制）、登录页 `WEB/src/app/login/page.tsx` + `WEB/src/components/LoginForm.tsx` + `WEB/src/app/login/actions.ts`（Server Action）、改密页 `WEB/src/app/change-password/page.tsx` + `WEB/src/components/ChangePasswordForm.tsx` + `actions.ts`、退出 `WEB/src/app/logout/route.ts`（删 session 并调用后端吊销）、统一处理 UnauthorizedError（清除 session 并重定向登录页）；使 T043–T045 转绿
- [x] T055 [US2] Playwright 旅程 `WEB/tests/e2e/login.spec.ts`：Compose 起依赖 + 打包 CLI 造数据 → 三个受保护页分别被重定向 → 登录 → 首次强制改密 → 进入主界面 → 退出后再访问被重定向；错误密码与不存在 Account 文案一致；后端吊销 Token 后再点击导航回到登录页

**Checkpoint**: US2 独立可用：quickstart 场景 B、D 通过，后端与前端覆盖率 ≥ 90%

---

## Phase 5: User Story 3 - 登录后通过左侧导航查看 Tenant、Module、User (Priority: P2)

**Goal**: 左侧导航恰好三项；三个只读分页列表；空状态；不展示任何 Credential 信息。

**Independent Test**: 登录后依次访问三页，内容与 CLI 数据一致（quickstart 场景 C）。

### Tests for User Story 3（先写，确认失败）⚠️

- [x] T056 [P] [US3] 集成测试 `CORE_T/tenant/ListQueriesIT.java`：Tenant、订阅、User 三个分页查询（page 默认 0、size 默认 20 最大 100、按创建时间倒序且 id 次序稳定、返回 total）；User 列表的 Tenant Slug 经 `TenantQueryService` 接口组装（不联表）；无 Account 的 User 也出现且 `accountUsernames` 为空；User 数据不含任何 Credential 字段
- [x] T057 [P] [US3] API 契约测试 `API_T/ListApiContractIT.java`：`/v1/tenants`、`/v1/module-subscriptions`、`/v1/users` 响应与 OpenAPI 一致；无 Token 401；改密前 403；size 超 100 被约束；Opaque Token 与 Internal JWT 都可访问
- [x] T058 [P] [US3] 前端组件测试 `WEB/tests/component/Sidebar.test.tsx`（恰好三项：租户管理、模块管理、用户管理；当前路由高亮；`aria-current`）
- [x] T059 [P] [US3] 前端组件测试 `WEB/tests/component/DataTable.test.tsx`（空状态文案、分页控件、只读：无新增/编辑/删除入口）与三页测试 `WEB/tests/component/TenantsPage.test.tsx`、`ModulesPage.test.tsx`、`UsersPage.test.tsx`（列：Tenant=标识/名称/状态/创建时间；Module=Module/所属 Tenant/订阅状态/订阅时间；User=Display Name/关联 username/所属 Tenant/状态/创建时间）
- [x] T060 [P] [US3] 性能与数据一致性测试 `API_T/ListPerformanceIT.java`：500 条 User 下首页查询在 2 秒内返回（SC-008），且列表与 CLI 写入数据一致（SC-003）

### Implementation for User Story 3

- [x] T061 [P] [US3] 实现查询：`CORE/tenant/TenantQueryService.java`（Tenant 列表、订阅列表、按 id 批量取 Tenant Slug）、`CORE/uam/UserQueryService.java`（User 列表含 username 聚合，只读视图 DTO 不含 Credential）
- [x] T062 [US3] 实现 `API/tenant/TenantController.java`、`API/tenant/ModuleSubscriptionController.java`、`API/uam/UserController.java`（经 `RequestAuthenticationFilter` 保护；分页参数校验）；使 T056、T057、T060 转绿
- [x] T063 [P] [US3] 实现前端布局：`WEB/src/app/(console)/layout.tsx`、`WEB/src/components/Sidebar.tsx`（三项固定导航）、`WEB/src/components/DataTable.tsx`（空状态、分页、只读）
- [x] T064 [US3] 实现三个页面：`WEB/src/app/(console)/tenants/page.tsx`、`modules/page.tsx`、`users/page.tsx`（服务端取数、`searchParams` 分页、根路径 `/` 重定向到 `/tenants`）；使 T058、T059 转绿
- [x] T065 [US3] Playwright 全旅程 `WEB/tests/e2e/console.spec.ts`（复用 T055 的数据夹具与登录步骤）：左侧导航三项 → 三页内容与数据一致、无写入口；含一个无 Account 的 User 与分页场景

**Checkpoint**: 三个故事均独立可用；quickstart 场景 A/B/C/D 全部通过

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 门禁、文档定稿与归档

- [x] T066 [P] 保密与安全复核：在全仓库与测试产物中检索测试口令、`password`、`tk_` 明文泄漏（含 `X-Session-Id` 头值）（日志、Audit、页面、响应）；确认无密钥入库（含 `*.pem`）、`.env.example` 无真实值；记录结果于 PR 说明
- [x] T067 [P] 覆盖率门禁：运行后端 JaCoCo 与前端 Vitest 覆盖率检查，行覆盖率均 ≥ 90%；若低于阈值，补测试而非降低阈值
- [x] T068 [P] 在 `.github/workflows/ci.yml` 配置 CI：后端 lint + test + coverage、前端 lint + typecheck + test + coverage + build（命令与 `backend/AGENTS.md`、`apps/platform-web/AGENTS.md` 一致）
- [x] T069 设计文档定稿：核对 T007 的回写与最终实现一致，去掉“待落地后定稿”标注，补充 Internal JWT 的最终契约与表前缀说明
- [x] T070 归档：将本 feature 的现状沉淀到 `docs/achieve-specs/`（新建 `tenant-core-mvp.md`：Ubiquitous Language 术语表、User/Account 模型、CLI 与 API 行为、Token 与 Internal JWT、已落地边界）
- [x] T071 按 [quickstart.md](quickstart.md) 场景 A/B/C/D 在本地完整走查一遍，核对 SC-001 至 SC-008（SC-001、SC-002、SC-008 的前端首屏为手工验证），并在 PR 说明中列出验证结果

---

## Phase 7: 技术栈升级与界面改造（2026-11-07 用户决定：Spring Boot 4.1、PostgreSQL 18、ArchUnit、shadcn/ui、自适应与中文界面）

**Purpose**: 实现 FR-024、FR-025、SC-009，并升级技术栈。后端（T072–T078）与前端（T079–T085）互相独立，可并行；每组内先写失败的测试再实现。背景与风险见 plan.md「技术栈变更」与 research R15–R18。

### 后端：ArchUnit、PostgreSQL 18、Spring Boot 4

- [x] T072 先写失败的架构测试（`com.tngtech.archunit:archunit-junit6` 1.5.1，test scope，版本在 `backend/pom.xml` 管理；用 `@AnalyzeClasses` 与 `@ArchTest` 声明规则）：`backend/core-lib/src/test/java/tech/toolkit/tenantcore/architecture/ArchitectureTest.java`（research R18 的规则①–⑥）、`backend/tenant-core-api/src/test/java/tech/toolkit/tenantcore/api/ApiArchitectureTest.java`、`backend/tenant-core-cli/src/test/java/tech/toolkit/tenantcore/cli/CliArchitectureTest.java`；确认规则⑥因缺少 `toString()` 而失败
- [x] T073 让架构测试转绿：为持有秘密字段的类补 `toString()`（API 的 `LoginBody`、`ChangePasswordBody`、`LoginResponse`；core-lib 的 `IssuedToken`、`IssuedJwt`、`ExchangeResult` 等，只输出非秘密字段），并修正其余违规；不改变任何对外行为
- [x] T074 升级 PostgreSQL 18：`deploy/docker-compose.yml`（镜像 `postgres:18`，数据卷挂载点 `/var/lib/postgresql`）、`backend/core-lib/src/test/java/tech/toolkit/tenantcore/support/PostgresTestBase.java`（镜像 `postgres:18`）；`SchemaConstraintsIT` 与全部集成测试在 18 上全绿；更新 `backend/AGENTS.md` 与 `docs/achieve-specs/tenant-core-mvp.md` 中的版本说明
- [x] T075 升级 Spring Boot 4.1.2：`backend/pom.xml`（父工程版本，删除不再需要的版本固定，如 `testcontainers.version`）、`backend/tenant-core-api/pom.xml`（`spring-boot-starter-web` → `spring-boot-starter-webmvc`）、`backend/core-lib/pom.xml` 与 `backend/tenant-core-cli/pom.xml`（Testcontainers 2 坐标 `testcontainers-postgresql`、`testcontainers-junit-jupiter`）；`mvn -DskipTests package` 通过
- [x] T076 适配测试基础设施并保持行为不变：`PostgresTestBase`（`org.testcontainers.postgresql.PostgreSQLContainer`）、`ApiTestBase`（`spring-boot-resttestclient`、`@AutoConfigureTestRestTemplate`、Jackson 3 的 `tools.jackson.databind`）及相关 import；在 `ListApiContractIT` 增加断言：`createdAt`、`subscribedAt`、`expiresAt` 仍是 ISO-8601 字符串；现有全部后端测试与 API 契约测试不改断言即可通过
- [x] T077 后端整体门禁：`mvn verify` 通过（含 ArchUnit、打包 CLI 冒烟、每个子项目行覆盖率 ≥ 90%、Spotless）；CI 配置无需改动则在 PR 说明里说明
- [x] T078 更新文档：`backend/AGENTS.md`（技术栈版本、ArchUnit 规则位置）、plan/research 的版本号核对、`docs/achieve-specs/tenant-core-mvp.md` 的技术栈与验证状态

### 前端：shadcn/ui、自适应、中文界面

- [x] T079 [P] 引入 Tailwind CSS 4 与 shadcn/ui：`apps/platform-web/package.json`（`tailwindcss`、`@tailwindcss/postcss`、`class-variance-authority`、`clsx`、`tailwind-merge`、`lucide-react`、`tw-animate-css`、Radix）、`postcss.config.mjs`、`components.json`、`src/app/globals.css`（Tailwind 引入与主题变量）、`src/lib/utils.ts`（`cn`）；用 shadcn CLI 生成 `src/components/ui/{button,input,label,card,table,badge,alert,sheet,separator}.tsx`；`vitest.config.ts` 把 `src/components/ui/**` 列入覆盖率排除并在 `apps/platform-web/AGENTS.md` 说明；`tests/setup` 补 `matchMedia`、`ResizeObserver` polyfill
- [x] T080 [P] 先写失败的单元测试 `apps/platform-web/tests/unit/format.test.ts`：状态文案（`ACTIVE`→正常、`SUSPENDED`→已暂停、`DISABLED`→已停用、`SUBSCRIBED`→已订阅）、时间按给定时区格式化、空用户名显示“—”、`PASSWORD_POLICY` 只映射为中文规则提示；再实现 `src/lib/format.ts`
- [x] T081 [P] 先写失败的组件测试：`tests/component/AppShell.test.tsx`（桌面侧边栏三项且当前项 `aria-current`；窄屏顶部栏有菜单按钮，点开 Sheet 后出现三个导航项和“退出登录”）、`tests/component/ResponsiveList.test.tsx`（同时渲染 Table 与卡片两种结构并带断点类名；中文列头；状态 Badge；空状态）、`LoginForm.test.tsx` 与 `ChangePasswordForm.test.tsx`（居中 Card、错误只显示中文、输入框与按钮不小于 44px 的类名）；断言基于 DOM 与类名，不依赖 CSS 计算
- [x] T082 实现界面：`src/components/AppShell.tsx`（替换 `Sidebar` 的布局，宽屏侧边栏、窄屏顶部栏 + `Sheet` 菜单，`(console)/layout.tsx` 使用它）、`src/components/ResponsiveList.tsx`（取代 `DataTable`，`sm` 以上 Table、以下卡片）、`src/components/LocalTime.tsx`（客户端按本地时区渲染，服务端输出 `<time dateTime>`，避免 hydration 不一致）、三个列表页与登录、改密页改用 shadcn 组件与中文文案；使 T080、T081 转绿，`pnpm test:coverage` 行覆盖率 ≥ 90%
- [x] T083 Playwright 自适应 E2E `apps/platform-web/tests/e2e/responsive.spec.ts`：在 360×740、768×1024、1280×800 三种视口下，登录、改密和三个列表页都无横向滚动（`document.documentElement.scrollWidth <= innerWidth`）；窄屏菜单可展开并导航、可退出；< 640px 列表为卡片、≥ 768px 为表格；同步修改 `console.spec.ts` 中依赖英文列头的断言
- [x] T084 在 Chrome 中按 quickstart 场景 E 手工走查 360px、768px、1280px，保存截图作为验证依据，并核对 SC-009
- [x] T085 更新文档：`apps/platform-web/AGENTS.md`（目录、命令、shadcn 约定、覆盖率排除）、`docs/achieve-specs/tenant-core-mvp.md`（界面与验证状态）

---

## Phase 8: 界面骨架对齐主流管理后台（2026-11-07 用户决定做；FR-026、FR-027、FR-028、SC-010；**实现前须用户确认本方案**）

**Purpose**: 增加顶栏与用户菜单，统一页头。只改 `apps/platform-web`，后端与契约不变。每步先写失败的测试再实现。

- [x] T086 [P] 先写失败的测试：`tenantCoreClient.me()` 已存在，只需确认返回 `displayName`、`accountUsername`、`tenantSlug`（核对 `src/lib/types.ts` 的 `Me`）；`tests/component/UserMenu.test.tsx`（触发按钮显示 Display Name；展开后显示 Display Name、username、Tenant Slug 与“退出登录”；“退出登录”是 `POST /logout` 的表单；触发区域 `h-11`/`min-h-11`）
- [x] T087 [P] 先写失败的组件测试：`tests/component/TopBar.test.tsx`（产品名“Platform”；窄屏菜单按钮在最左；右侧是用户菜单）、`tests/component/PageHeader.test.tsx`（标题；`actions` 插槽为空时不渲染容器）、更新 `tests/component/AppShell.test.tsx`（侧边栏只有三项且无“退出登录”；折叠菜单只有三项；内容区有最大宽度类）
- [x] T088 实现：`src/components/ui/dropdown-menu.tsx`（`pnpm dlx shadcn@latest add dropdown-menu`，不手工编辑）、`src/components/UserMenu.tsx`、`src/components/TopBar.tsx`、`src/components/PageHeader.tsx`；改 `AppShell.tsx`（顶栏 + 侧边栏 + 限宽内容区；去掉侧边栏与 Sheet 里的退出登录）、`(console)/layout.tsx`（服务端取 me 并传 props，Opaque Token 不进客户端）、三个列表页改用 `PageHeader`
- [x] T089 更新 E2E：`tests/e2e/responsive.spec.ts`（折叠菜单只含三项；三种视口下顶栏可见、用户菜单可达且可退出）、`console.spec.ts`/`login.spec.ts`（退出登录改走用户菜单；`fixtures.ts` 增加 `logoutViaUserMenu(page)`）；新增 `tests/e2e/shell.spec.ts`（顶栏、用户菜单、退出登录唯一入口、页头；当前版本标签仍为 `@1.0.0`，主旅程另带 `@happy-path`）；`pnpm e2e --tag 1.0.0` 与 `pnpm e2e` 均须通过
- [x] T090 门禁：`pnpm lint`、`pnpm typecheck`、`pnpm test:coverage`（行覆盖率 ≥ 90%）、`pnpm build` 通过；用 `pnpm e2e --chrome` 全量通过
- [x] T091 在 Chrome 中按 quickstart 场景 F 走查 360px、768px、1280px 并截图，核对 SC-010；更新 `apps/platform-web/AGENTS.md`（目录、组件）与 `docs/achieve-specs/tenant-core-mvp.md`（界面骨架）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**：无依赖，可立即开始；**T007（设计文档回写）须在任何实现任务前完成**
- **Phase 2 Foundational**：依赖 Phase 1，**阻塞**所有用户故事
- **US1（Phase 3）**：依赖 Phase 2
- **US2（Phase 4）**：后端依赖 Phase 2 与 US1 的服务任务 **T026–T030**（集成测试用它们造数据）；T036、T039、T046、T048 等只依赖 Phase 2，可提前；前端任务（T043–T045、T053–T054）可与 US1 并行；T055 依赖 T035（打包 CLI）
- **US3（Phase 5）**：依赖 US2 的认证（T052、T054）；T056、T061 只依赖 Phase 2 与 US1 服务，可提前
- **Polish（Phase 6）**：依赖需要交付的故事全部完成
- **Phase 8**：依赖 Phase 7；T086、T087 可并行，T088 依赖两者，T089 依赖 T088，T090、T091 最后
- **Phase 7**：依赖 Phase 6；后端组（T072–T078）与前端组（T079–T085）可并行；T072 先于 T073，T075 先于 T076，T079 先于 T082

### Within Each User Story

- 测试先写并确认失败 → 模型/仓储 → 服务 → 入口（CLI/API/页面）→ 集成/E2E
- T029 依赖 T014、T027、T028；T047 依赖 T029、T046；T049 依赖 T046、T048；T050 依赖 T046–T049；T052 依赖 T050、T051；T054 依赖 T053；T062 依赖 T061、T052；T064 依赖 T063

### Parallel Opportunities

- Phase 1：T002–T006 彼此并行（T007 与它们也无文件冲突）
- Phase 2：T013–T016 并行（T008–T012 顺序执行）
- US1 测试 T017–T025 全部并行；实现 T026–T028、T030–T032 并行
- US2 测试 T036–T045 全部并行；T046、T048、T051、T053 并行；前端（T053–T054）与后端可由不同人并行
- US3 测试 T056–T060 并行；后端 T061–T062 与前端 T063–T064 并行

### Parallel Example: User Story 1

```text
# 并行写所有失败测试：
T017 TenantServiceIT   T018 LoginMethodServiceIT   T019 UserServiceIT
T020 AccountServiceIT  T021 SubscriptionServiceIT  T022 TenantDetailIT
T023 CliContractIT     T024 PasswordLeakIT         T025 PasswordReaderTest

# 并行实现不互相依赖的服务：
T026 Tenant   T027 Login Method   T028 User   T030 订阅   T032 PasswordReader
```

---

## Implementation Strategy

### MVP First

1. 完成 Phase 1（含 T007 设计文档回写）、Phase 2
2. 完成 US1（CLI Bootstrap）并独立验证 → 数据起点就绪
3. 完成 US2（登录与 Token）→ 与 US1 合起来构成最小可用闭环（P1 + P1）
4. 完成 US3（导航与列表）→ 可见成果，达成 spec 全部范围
5. Phase 6 收尾：门禁、设计文档定稿、归档

### Incremental Delivery

- 每个故事完成即满足各自 Checkpoint，可单独演示
- 若时间受限，最小可交付 = US1 + US2

---

## Notes

- 所有 `[P]` 任务操作不同文件、无未完成依赖
- 先红后绿：每个故事先提交失败测试的证据，再实现
- 命名遵循 [spec.md](spec.md) 的术语表与 [plan.md](plan.md) 的命名映射：类名、方法、变量、表名、字段、API、CLI 与测试名使用同一个英文业务术语，不另造同义词；新增业务概念先登记术语表
- 任何需求/设计变更先更新 `spec.md` / `plan.md` / 本文件并经确认（根 `AGENTS.md`）
- 初始密码、哈希、Token（含 Internal JWT）不得出现在任何日志、输出、Audit、测试快照中
- 私钥只来自环境变量或挂载文件，永不入库
