---

description: "Task list for 002-admin-center-uam：Admin Center 用户管理与会话管理"
---

# Tasks: Admin Center 用户管理与会话管理（User Manager / Session Manager）

**Input**: Design documents from `/specs/002-admin-center-uam/`

**Prerequisites**: [plan.md](./plan.md)、[spec.md](./spec.md)、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)（OpenAPI 与 Web 路由）、[quickstart.md](./quickstart.md)、[prototype/](./prototype/README.md)（已确认）

**Tests**: **必需**（不是可选）。根 `AGENTS.md`「测试先行」要求每个新行为先写会失败的测试再实现（red → green → refactor），单元测试行覆盖率 **≥ 90%**。因此每个用户故事都先列「测试」任务，**它们必须先失败**，再做实现任务。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。`[USn]` 对应 spec 中的 User Story n。

## Format: `[ID] [P?] [Story] Description`

- **[P]**：可并行（不同文件、无未完成的依赖）
- **[Story]**：所属用户故事（US1 … US12）；Setup、Foundational、Polish 阶段没有
- 描述里给出确切路径；字段约束逐字引用自 [data-model.md](./data-model.md)

## Path Conventions

- UAM（Spring Boot）：`apps/uam/`，Java 根包 `com.s2u2m.uam`，即 `apps/uam/src/main/java/com/s2u2m/uam/{api,application,domain,infrastructure,cli}/`；资源 `apps/uam/src/main/resources/`；测试 `apps/uam/src/test/java/com/s2u2m/uam/{unit,integration,contract,concurrency,architecture,support}/`
- Admin Center（Next.js）：`apps/admin-center/`，源码 `src/{app,components,lib}/`，测试 `tests/{unit,contract,e2e,support}/`
- 共享契约：`contracts/uam/openapi.yaml`（T005 迁入）；本地与多实例编排 `infra/local/`；压测 `infra/load/k6/`；CI `.github/workflows/`
- 术语：**客户端（Client）** 指调用 UAM 的服务（不是浏览器）；本期唯一的客户端是 `admin-center`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 两个应用的项目骨架、契约迁移、CI 与本地依赖

- [X] T001 创建 UAM Maven 工程 `apps/uam/pom.xml`（Spring Boot 4.0.x 父工程，Java 21；依赖：`spring-boot-starter-web`、`-validation`、`-jdbc`、`-actuator`、`-data-redis`（Lettuce）、`spring-security-crypto`、`org.bouncycastle:bcprov-jdk18on`、`flyway-core` 与 `flyway-database-postgresql`（社区版）、`postgresql`、Micrometer；测试依赖：`spring-boot-starter-test`、Testcontainers（`junit-jupiter`、`postgresql`）、`archunit-junit5`、OpenAPI 请求校验库；插件：JaCoCo 行覆盖率 ≥ 0.90，排除 `**/UamApplication.class`、`**/config/*Config.class`、`**/generated/**`；Spotless + google-java-format）与启动类 `apps/uam/src/main/java/com/s2u2m/uam/UamApplication.java`
- [X] T002 [P] 编写 `apps/uam/AGENTS.md` 与 `apps/uam/CLAUDE.md`（内容 `@AGENTS.md`）：技术栈与目录、命令（lint=`mvn -f apps/uam/pom.xml spotless:check`、typecheck=`-DskipTests compile`、test、`verify` 含覆盖率门禁）、覆盖率口径（行覆盖率 ≥ 90%，排除路径同 T001）、环境变量表（`UAM_CLIENTS`、会话与限流默认值）
- [X] T003 [P] 初始化 Admin Center 工程 `apps/admin-center/`：`package.json`（脚本 `dev`、`lint`、`typecheck`、`test`、`test:cov`（`coverageThreshold` 行覆盖率 90，排除 `src/components/ui/**`、`**/*.generated.ts`、`src/**/*.d.ts`）、`build`、`e2e`、`gen:api`）、`tsconfig.json`、`next.config.ts`、`eslint.config.mjs`、`jest.config.ts`、`jest.setup.ts`、`playwright.config.ts`（每个视口宽度 320 / 375 / 768 / 1024 / 1440 / 2560 一个 project）、`components.json`、`.env.example`（仅占位值）；Next.js 15、React 19、TypeScript 5、Tailwind CSS 4、zod、msw
- [X] T004 [P] 编写 `apps/admin-center/AGENTS.md` 与 `apps/admin-center/CLAUDE.md`：技术栈与目录、命令、覆盖率口径与排除路径、环境变量表（`UAM_BASE_URL`、`UAM_SERVICE_KEY`、`UAM_TIMEOUT_MS`、`SESSION_COOKIE_NAME`、`TRUSTED_PROXY_HOPS`）、「`src/lib` 下的服务端模块一律 `import "server-only"`」
- [X] T005 把 `specs/002-admin-center-uam/contracts/uam/openapi.yaml` 迁到 `contracts/uam/openapi.yaml`（`git mv`，作为唯一权威），在原位置留 `specs/002-admin-center-uam/contracts/uam/README.md` 指向新位置，并修正 `plan.md`、`research.md`、`quickstart.md`、`contracts/web-routes.md`、`prototype/README.md`、本文件中的链接
- [X] T006 [P] 更新仓库根 `.gitignore`：忽略 `apps/uam/target/`、`apps/admin-center/{node_modules,.next,coverage,playwright-report,test-results}/`、`.env*`（保留 `.env.example`）
- [X] T007 [P] 创建 CI `.github/workflows/uam.yml`：JDK 21，`spotless:check`、`compile`、`mvn verify`（Testcontainers + JaCoCo 行覆盖率 ≥ 90% 门禁）
- [X] T008 [P] 创建 CI `.github/workflows/admin-center.yml`：`npm ci`、`lint`、`typecheck`、`test:cov`（≥ 90%）、`build`
- [X] T009 [P] 创建本地依赖编排 `infra/local/docker-compose.yml`（PostgreSQL 16；Redis 7，开启 AOF `everysec`）与 `infra/local/.env.example`；UAM 的端口**不映射到宿主机**
- [X] T010 [P] 在 `apps/admin-center/src/components/ui/` 加入用到的 shadcn/ui 组件：`button`、`input`、`label`、`card`、`alert`、`avatar`、`badge`、`table`、`alert-dialog`、`dialog`、`dropdown-menu`、`sidebar`、`sheet`、`breadcrumb`、`tooltip`、`separator`
- [X] T011 [P] 实现 `apps/admin-center` 的 `gen:api` 脚本：由 `contracts/uam/openapi.yaml` 生成 `apps/admin-center/src/lib/uam/types.generated.ts`（提交生成结果）
- [X] T012 [P] 编写 `apps/uam/Dockerfile`（JDK 21 运行时，可执行 jar）、`apps/admin-center/Dockerfile`（Node LTS，Next.js standalone 输出）以及各自的 `.dockerignore`

---

## Phase 2: Foundational（阻塞性前提）

**Purpose**: 所有用户故事都依赖的基础设施：数据库结构、统一错误、客户端认证、操作人上下文、写操作日志、Admin Center 的配置 / UAM 客户端 / 会话三态 / 外壳

**⚠️ CRITICAL**: 本阶段完成前，不能开始任何用户故事

### UAM：测试先行

- [X] T013 [P] 测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/MigrationIT.java`：迁移可应用，并断言 `app_user.status` 的 `CHECK` 只允许 `ACTIVE` / `DISABLED`；`account.kind` 的 `CHECK` 只允许 `PASSWORD`；`account.status` 只允许 `ACTIVE` / `DISABLED` / `DELETED`；`account.login_name_norm` 上有**全局唯一索引**（含已停用用户的账号）；`account.user_id` 外键 → `app_user.id` 且有索引；`credential.account_id` 是主键并外键 → `account.id`；`app_user.name` 与 `account.login_name_norm` 上有 `pg_trgm` GIN 索引；`app_user.status` 上有普通索引
- [X] T014 [P] 测试基座 `apps/uam/src/test/java/com/s2u2m/uam/support/IntegrationTestBase.java`：Testcontainers 启动 PostgreSQL 16 与 Redis 7，`@DynamicPropertySource` 注入，每个测试前清库与清 Redis
- [X] T015 [P] 测试 `apps/uam/src/test/java/com/s2u2m/uam/architecture/ArchitectureTest.java`（ArchUnit）：依赖方向 `api → application → domain ← infrastructure`；`domain` 不依赖 Spring 与任何框架；`cli` 只依赖 `application`
- [X] T016 [P] 测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/ProblemAdviceTest.java`：错误体为 RFC 9457 `application/problem+json`，带稳定的 `code`（`invalid_credentials`、`session_invalid`、`client_unauthenticated`、`client_forbidden`、`operator_required`、`throttled`、`account_name_taken`、`validation_failed`、`cannot_disable_self`、`cannot_disable_last_active_user`、`cannot_terminate_own_session`、`session_not_found`、`user_not_found`、`current_password_incorrect`、`weak_password`、`same_as_current`）；字段级错误 `errors[{field, reason}]`；`throttled` 带 `Retry-After`；响应不含密码、哈希、会话标识
- [X] T017 [P] 测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/ClientRegistryTest.java`：解析 `UAM_CLIENTS`（`id`、`keyHashes`、`permissions`）；密钥 `uamk_…` 的 SHA-256 哈希（`sha256:<hex>`）常量时间比较；未知密钥返回空
- [X] T018 [P] 测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/ClientAuthFilterTest.java`：缺失、无效的服务密钥 → 401 `client_unauthenticated`，响应**不透露**是哪一部分不对；有效 → 放行并在请求属性里带客户端 `id`
- [X] T019 [P] 测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/OperatorContextResolverTest.java`：解析 `X-Operator-User-Id`、`X-Operator-Account-Id`、`X-Operator-Session-Ref`（UUID）；写操作（`POST` / `DELETE`，登录相关除外）缺失 → 400 `operator_required`；读操作可选
- [X] T020 [P] 测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/WriteOperationLoggerTest.java` 与 `LogRedactionTest.java`：事件名 `uam.admin.write`，字段 `ts`、`operatorUserId`、`operatorAccountId`、`clientId`、`action`（`user.create` / `user.disable` / `user.enable` / `session.terminate` / `account.password.change`）、`targetType`、`targetId`、`outcome`（`success` / `rejected` / `failed`）、`reason`、`traceId`；被拒绝的操作也记录；用日志捕获断言**永不出现**密码、密码哈希、会话标识、服务密钥

### UAM：实现

- [X] T021 创建 Flyway 迁移 `apps/uam/src/main/resources/db/migration/V1__init.sql`：`CREATE EXTENSION pg_trgm`；表 `app_user`（`id uuid` 主键；`name varchar(64)` 非空；去首尾空白后 1–64 字符；**不要求唯一**；`status text` 非空；`ACTIVE` / `DISABLED`（`CHECK`）；`created_at timestamptz` 非空，默认 `now()`；`updated_at timestamptz` 非空；索引：`name` 上 `pg_trgm` GIN（包含匹配）；`status` 上普通索引（统计正常用户数））、`account`（`id uuid` 主键；`user_id uuid` 非空，外键 → `app_user.id`；索引；`kind text` 非空；本期仅 `PASSWORD`（`CHECK`，日后扩展时放宽）；`login_name varchar(32)` 非空；创建时的原样账号名，**创建后不可改**；`login_name_norm varchar(32)` 非空；`lower(login_name)`；**全局唯一索引**（含已停用用户的账号）；`pg_trgm` GIN（搜索）；`status text` 非空；`ACTIVE` / `DISABLED` / `DELETED`；`DELETED` 为预留；`created_at timestamptz` 非空，默认 `now()`）、`credential`（`account_id uuid` 主键，外键 → `account.id`；`password_hash text` 非空；Argon2id（`DelegatingPasswordEncoder` 格式，可升级）；明文永不落库、不进日志；`updated_at timestamptz` 非空）；`app_user.updated_at` 与 `credential.updated_at` 没有数据库默认值，由仓储在每次插入与更新时显式设置（US1 的 JDBC 仓储、US5 的状态更新、US7 的改密各自负责）；使 T013 通过
- [X] T022 配置 `apps/uam/src/main/resources/application.yml` 与 `apps/uam/src/main/resources/logback-spring.xml`：默认值 `uam.session.idle=30m`、`uam.session.absolute=12h`、`uam.session.touch-interval=60s`、`uam.login.throttle.max-failures=5`、`uam.login.throttle.block=15m`、`uam.password.min-length=10`、`uam.clients` 读自 `UAM_CLIENTS`；结构化 JSON 日志；`server.shutdown=graceful`；存活 / 就绪探针（就绪依赖 PostgreSQL 与 Redis）；Micrometer 指标；传递并记录 W3C `traceparent`
- [X] T023 [P] 实现 `Clock` 端口与 `SystemClock`：`apps/uam/src/main/java/com/s2u2m/uam/domain/port/Clock.java`、`apps/uam/src/main/java/com/s2u2m/uam/infrastructure/SystemClock.java`
- [X] T024 实现统一错误：`apps/uam/src/main/java/com/s2u2m/uam/domain/DomainException.java`（带稳定 `code`）、`apps/uam/src/main/java/com/s2u2m/uam/api/ProblemAdvice.java`；使 T016 通过
- [X] T025 实现客户端认证：`apps/uam/src/main/java/com/s2u2m/uam/infrastructure/security/ClientRegistry.java`（SHA-256 常量时间比较）与 `apps/uam/src/main/java/com/s2u2m/uam/api/ClientAuthFilter.java`（`Authorization: Bearer <服务密钥>`；本任务只做识别与 401，权限 403、双密钥轮换、拒绝日志在 US12）；使 T017、T018 通过
- [X] T026 实现操作人上下文：`apps/uam/src/main/java/com/s2u2m/uam/domain/model/OperatorContext.java`（`userId`、`accountId`、`sessionRef`）与 `apps/uam/src/main/java/com/s2u2m/uam/api/OperatorContextResolver.java`；使 T019 通过
- [X] T027 实现写操作日志：`apps/uam/src/main/java/com/s2u2m/uam/domain/port/WriteOperationLog.java`、`apps/uam/src/main/java/com/s2u2m/uam/infrastructure/logging/WriteOperationLogger.java`、`apps/uam/src/main/java/com/s2u2m/uam/infrastructure/logging/LogRedaction.java`；使 T020 通过
- [X] T028 [P] 配置 Redis 与健康检查：`apps/uam/src/main/java/com/s2u2m/uam/infrastructure/config/RedisConfig.java`（Lettuce，单主；撤销类写入后 `WAIT 1 <短超时>` 的辅助方法）

### Admin Center：测试先行

- [X] T029 [P] 测试 `apps/admin-center/tests/unit/lib/config.test.ts`：缺少 `UAM_SERVICE_KEY` 或 `UAM_BASE_URL` 时启动失败；默认值 `UAM_TIMEOUT_MS=1500`、`SESSION_COOKIE_NAME=__Host-asid`；`TRUSTED_PROXY_HOPS` 解析；并在 `apps/admin-center/tests/unit/instrumentation.test.ts` 断言：服务启动时（`register()`）缺少 `UAM_SERVICE_KEY` 或 `UAM_BASE_URL` 即抛错；`next build` 在未设置运行期环境变量时不受影响
- [X] T030 [P] 测试基座 `apps/admin-center/tests/support/uam-stub.ts`（msw：按 `contracts/uam/openapi.yaml` 校验请求与响应）及契约测试 `apps/admin-center/tests/contract/uam-client.contract.test.ts`
- [X] T031 [P] 测试 `apps/admin-center/tests/unit/lib/uam/client.test.ts`：每个请求带 `Authorization: Bearer <UAM_SERVICE_KEY>`；写操作带 `X-Operator-User-Id` / `X-Operator-Account-Id` / `X-Operator-Session-Ref`；超时 1.5 秒 → `unavailable`；401 `client_unauthenticated` 与 403 `client_forbidden` → `misconfigured`（**不是** `session_invalid`）；5xx → `unavailable`；问题码到界面文案的映射表与 `contracts/web-routes.md` §5 一致
- [X] T032 [P] 测试 `apps/admin-center/tests/unit/lib/session/get-session.test.ts`：无 cookie → `anonymous`；`verify` 200 → `authenticated`；401 `session_invalid` → `anonymous`；超时、5xx、`misconfigured` → `unverified`；单个请求内去重（React `cache`）
- [X] T033 [P] 测试 `apps/admin-center/tests/unit/app/session-expire.test.ts`：`GET /session/expire` 重新 `verify` 仍无效才清除 cookie 并 303 到 `/login`；`unverified` 时**不清** cookie
- [X] T034 [P] 测试 `apps/admin-center/tests/unit/lib/security/origin-check.test.ts` 与 `apps/admin-center/tests/unit/lib/session/client-ip.test.ts`：同源校验（`Origin` / `Sec-Fetch-Site`，失败 403；FR-033）；按 `TRUSTED_PROXY_HOPS` 从可信代理头取来源地址，不采信用户自带的多余 `X-Forwarded-For`
- [X] T035 [P] 测试 `apps/admin-center/tests/unit/middleware.test.ts`：设置 CSP（含 nonce）、`X-Content-Type-Options: nosniff`、`Referrer-Policy: same-origin`、`frame-ancestors 'none'`；不发网络请求
- [X] T036 [P] 测试 `apps/admin-center/tests/unit/components/shell/*.test.tsx`：已登录渲染侧边栏 + 顶栏、匿名与 `unverified` 只有顶栏；`unverified` 显示「服务暂不可用」横幅且保留登录状态；手机抽屉打开后焦点被限制、`Esc` 关闭并回到菜单按钮；用户菜单含「修改密码」「退出登录」；导航项「用户管理」「会话管理」，当前项 `aria-current="page"`

### Admin Center：实现

- [X] T037 实现 `apps/admin-center/src/lib/config.ts`（`server-only`，zod 解析环境变量；**不在模块加载时解析**，避免 `next build` 因构建环境缺变量而失败）；另新增 `apps/admin-center/src/instrumentation.ts`：在 `register()`（仅 `NEXT_RUNTIME === 'nodejs'`）里调用配置解析，缺失即抛错使服务启动失败；使 T029 通过
- [X] T038 实现 UAM 客户端 `apps/admin-center/src/lib/uam/client.ts` 与 `apps/admin-center/src/lib/uam/errors.ts`（`server-only`；带服务密钥与操作人请求头；超时 1.5 秒；传递 `traceparent`；错误码映射）；使 T030、T031 通过
- [X] T039 实现会话三态 `apps/admin-center/src/lib/session/get-session.ts` 与 `apps/admin-center/src/lib/session/cookie.ts`（cookie `__Host-asid`：`HttpOnly; Secure; SameSite=Strict; Path=/`，不设 `Domain`，`Max-Age` 取 `maxAgeSeconds`）；使 T032 通过
- [X] T040 实现 `apps/admin-center/src/app/session/expire/route.ts`；使 T033 通过
- [X] T041 [P] 实现 `apps/admin-center/src/lib/security/origin-check.ts` 与 `apps/admin-center/src/lib/session/client-ip.ts`；使 T034 通过
- [X] T042 实现 `apps/admin-center/src/middleware.ts`；使 T035 通过
- [X] T043 实现外壳：`apps/admin-center/src/components/shell/{app-shell,app-header,app-sidebar,user-menu,breadcrumbs,nav-config}.tsx`，`apps/admin-center/src/app/layout.tsx`、`globals.css`、`not-found.tsx`、`error.tsx`，`apps/admin-center/src/app/(shell)/layout.tsx`（读会话三态）、`apps/admin-center/src/app/(auth)/layout.tsx`（极简外壳）；桌面 ≥ 1024 侧边栏展开、平板 768–1023 图标条、手机 < 768 抽屉；展开偏好存非敏感 UI cookie `sidebar_state`；使 T036 通过
- [X] T044 [P] 搭建 E2E 栈：`infra/local/docker-compose.stack.yml`（PostgreSQL、Redis、UAM、Admin Center）、`infra/local/scripts/bootstrap.sh`（构建镜像并启动栈）与 `infra/local/e2e.env`（**仅限本地 E2E 的固定测试密钥**：`UAM_SERVICE_KEY=uamk_e2e_…`，以及与它的 `sha256` 对应的 `UAM_CLIENTS`；文件头注明「禁止用于任何其他环境」）、`apps/admin-center/tests/e2e/support/{stack,fixtures}.ts`（启停、造数、登录辅助）

**Checkpoint**: 基础就绪，可以开始用户故事。

---

## Phase 3: User Story 1 - 运维人员用账号登录，并被识别为所属用户（Priority: P1）🎯 MVP

**Goal**: 账号名 / 密码登录，cookie 返回会话标识；多账号登录识别为同一用户；一账号一会话；失败限制；未登录访问受保护页被带到登录页。

**Independent Test**: 准备一个关联两个账号的用户，用两个账号分别登录，都进入 Admin Center 且被识别为同一用户，cookie 里有会话标识；错误凭据被拒绝且没有会话。（FR-001 ~ FR-007、FR-012；quickstart S1 ~ S3）

### Tests for User Story 1 ⚠️（先写，确认失败）

- [X] T045 [P] [US1] 领域策略单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/domain/{AccountNameRulesTest,PasswordPolicyTest,SessionPolicyTest,LoginThrottlePolicyTest}.java`：账号名 3–32 个字母、数字或 `_` `.` `-`，大小写不敏感；密码默认至少 10 位且同时含字母与数字（可配置）；会话空闲 30 分钟、绝对 12 小时，Redis TTL 取两者剩余的较小值；登录失败同一「账号 + 来源地址」连续 5 次后拒绝该来源 15 分钟，不锁账号
- [X] T046 [P] [US1] 用例单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/application/LoginUseCaseTest.java`（用假端口）：账号不存在、密码错误、账号已停用、所属用户已停用，一律 `invalid_credentials`，且都先做一次哈希比对；成功则签发新会话并顶掉同账号旧会话；写入会话后复查用户状态（已停用则终止刚建的会话并拒绝）；达到限制阈值后即使凭据正确也 429
- [X] T047 [P] [US1] 用例单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/application/VerifySessionUseCaseTest.java`：有效会话返回会话数据（用户标识与名称、账号标识与账号名与种类、会话引用、到期时间）；无效、过期、被取代 → `session_invalid`；距上次写入超过 60 秒才续期
- [X] T048 [P] [US1] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/RedisSessionStoreIT.java`：登录脚本原子完成「读旧 → 删旧 → 写新 → 更新索引」；`uam:sess:{sha}` 里只有 `SHA-256(会话标识)`，没有明文；`uam:acct:{accountId}:sess`、`uam:sref:{ref}`、`uam:online` 同步更新；TTL 取「空闲剩余」与「绝对剩余」的较小值；续期只在超过 60 秒后写
- [X] T049 [P] [US1] 并发测试 `apps/uam/src/test/java/com/s2u2m/uam/concurrency/ConcurrentLoginIT.java`：同一账号 N 个并发登录，最终**恰好一个**有效会话
- [X] T050 [P] [US1] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/AuthContractIT.java`：`POST /v1/auth/login` 与 `POST /v1/auth/sessions/verify` 的请求与响应按 `contracts/uam/openapi.yaml` 校验；失败限制：同一账号与来源连错 5 次后，正确密码也得 429 + `Retry-After`，其他来源仍可登录
- [X] T051 [P] [US1] 时序测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/LoginTimingIT.java`：账号不存在与密码错误的响应耗时无可区分差别（伪哈希路径）
- [X] T052 [P] [US1] CLI 测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/cli/{InitAdminCommandTest,AddAccountCommandTest}.java`：`init-admin` 仅在没有任何用户时创建首个用户与账号，幂等，密码只经标准输入，按密码强度与账号名规则校验，日志写标准错误；`add-account` 给已有用户（`--user-id` 或 `--account-name-of`）加第二个「账号名 / 密码」账号，账号名重复被拒；另新增 `apps/uam/src/test/java/com/s2u2m/uam/unit/application/AccountProvisioningServiceTest.java`：单事务创建用户 + 账号 + 凭据（任一步失败不留半成品）；用户名去首尾空白后 1–64 字符；账号名 3–32 个字母、数字或 `_` `.` `-`，大小写不敏感且全局唯一；密码满足强度策略；可只给已有用户追加账号
- [X] T053 [P] [US1] 单元测试 `apps/admin-center/tests/unit/app/login-actions.test.ts`：空账号名或密码 → 必填提示且**不调用 UAM**；`invalid_credentials` → 「账号名或密码错误」；`throttled` → 提示稍后再试并带 `Retry-After`；成功写 cookie 并跳转；同源校验；`next` 只接受以单个 `/` 开头的站内路径（拒绝 `//`、带协议、含反斜杠），否则进入 `/users`
- [X] T054 [P] [US1] 组件测试 `apps/admin-center/tests/unit/components/auth/login-form.test.tsx`：字段错误、统一失败提示、不回填密码、`autocomplete` 为 `username` / `current-password`
- [X] T055 [P] [US1] E2E `apps/admin-center/tests/e2e/us1-login.spec.ts`：S1（账号 A、B 登录识别为同一用户）、S2（统一失败提示、限流）、S3（同账号第二个浏览器登录顶掉第一个）、未登录访问深链接登录后回到原页、站外 `next` 被忽略、已登录访问 `/login` 重定向到 `/users`；并记录从打开登录页到看到用户列表的耗时（SC-001：95% ≤ 30 秒含输入）

### Implementation for User Story 1

- [X] T056 [P] [US1] 值对象与枚举 `apps/uam/src/main/java/com/s2u2m/uam/domain/model/{UserStatus,AccountStatus,AccountKind,LoginName,SessionRef}.java`（`LoginName`：`raw` 与 `normalized`（`lower(login_name)`））
- [X] T057 [P] [US1] 实体 `apps/uam/src/main/java/com/s2u2m/uam/domain/model/{User,Account,Credential,Session}.java`（`Account.canLogin(user)` = 账号 `ACTIVE` 且所属用户 `ACTIVE`；`Session.isActive(now)`）
- [X] T058 [P] [US1] 策略 `apps/uam/src/main/java/com/s2u2m/uam/domain/policy/{AccountNameRules,PasswordPolicy,SessionPolicy,LoginThrottlePolicy}.java`；使 T045 通过
- [X] T059 [US1] 端口 `apps/uam/src/main/java/com/s2u2m/uam/domain/port/{UserRepository,AccountRepository,CredentialRepository,SessionStore,LoginThrottle,PasswordHasher}.java`
- [X] T060 [US1] JDBC 仓储 `apps/uam/src/main/java/com/s2u2m/uam/infrastructure/jdbc/{JdbcUserRepository,JdbcAccountRepository,JdbcCredentialRepository}.java`（显式 SQL；`login_name_norm = lower(login_name)`；插入时显式设置 `app_user.updated_at`、`credential.updated_at`，取 `now()`）
- [X] T061 [P] [US1] `apps/uam/src/main/java/com/s2u2m/uam/infrastructure/security/Argon2PasswordHasher.java`（`DelegatingPasswordEncoder` 默认 Argon2id，参数落后时登录成功后自动升级；伪哈希比对用的固定哈希）
- [X] T062 [US1] `apps/uam/src/main/java/com/s2u2m/uam/infrastructure/redis/RedisSessionStore.java` 与 Lua 脚本 `apps/uam/src/main/resources/redis/{login,touch}.lua`；键 `uam:sess:{sha}`、`uam:acct:{accountId}:sess`、`uam:sref:{ref}`、`uam:online`（成员 `{userId}|{ref}`，分数为空闲到期 epoch 秒，取空闲与绝对到期较小者）；使 T048、T049 通过
- [X] T063 [US1] `apps/uam/src/main/java/com/s2u2m/uam/infrastructure/redis/RedisGuards.java` 的登录限流部分：键 `uam:throttle:login:{sha256(规范化账号名|来源地址)}`，5 次后锁该来源 15 分钟，`Retry-After` 取剩余 TTL
- [X] T064 [US1] 用例与 DTO：`apps/uam/src/main/java/com/s2u2m/uam/application/{LoginUseCase,VerifySessionUseCase}.java`、`apps/uam/src/main/java/com/s2u2m/uam/api/dto/{LoginRequest,LoginResponse,VerifyRequest,VerifyResponse}.java`；另新增 `apps/uam/src/main/java/com/s2u2m/uam/application/AccountProvisioningService.java`（`createUserWithAccount`、`addAccount`；部署期命令与 US4 的 `CreateUserUseCase` 共用）；使 T046、T047、T051 与 T052 中的服务测试通过
- [X] T065 [US1] `apps/uam/src/main/java/com/s2u2m/uam/api/AuthController.java`：`POST /v1/auth/login`、`POST /v1/auth/sessions/verify`；使 T050 通过
- [X] T066 [US1] 部署期命令 `apps/uam/src/main/java/com/s2u2m/uam/cli/{InitAdminCommand,AddAccountCommand}.java`（`cli` profile：不启动 Web、不连 Redis、先跑 Flyway、日志写标准错误；创建逻辑一律调用 `AccountProvisioningService`，不自带；并给 `infra/local/scripts/bootstrap.sh` 增加 `init-admin` 步骤建首个用户，`apps/admin-center/tests/e2e/support/fixtures.ts` 的造数与登录辅助依赖它）；使 T052 通过
- [X] T067 [P] [US1] `apps/admin-center/src/lib/validation/login.ts`（zod）与 `apps/admin-center/src/lib/session/next-path.ts`（`next` 白名单）
- [X] T068 [US1] 登录页与表单 `apps/admin-center/src/app/(auth)/login/page.tsx`、`apps/admin-center/src/components/auth/login-form.tsx`；使 T054 通过
- [X] T069 [US1] 登录动作 `apps/admin-center/src/app/(auth)/login/actions.ts`（经 `client-ip.ts` 取 `clientIp`；成功写 cookie 并按白名单跳转）；使 T053 通过
- [X] T070 [US1] 受保护路由：`apps/admin-center/src/app/(shell)/layout.tsx` 对 `anonymous` 重定向 `/login?next=<原路径>`，已登录访问 `/login` 重定向 `/users`；`apps/admin-center/src/app/(shell)/page.tsx` 重定向 `/users`
- [X] T071 [US1] 验证：T045 ~ T055 全部通过（`apps/admin-center/tests/e2e/us1-login.spec.ts` 与 UAM 的 `mvn verify`）；按 [quickstart.md](./quickstart.md) 跑 S1 ~ S3

**Checkpoint**: US1 可独立运行与验证（MVP 的第一块）。

---

## Phase 4: User Story 2 - 在 User Manager 中浏览用户并查看其全部账号（Priority: P1）

**Goal**: 用户列表（分页、搜索）与用户详情（基本信息 + 全部账号）。依赖 US1（登录后才能访问界面）。

**Independent Test**: 预置含 0 / 1 / 多个账号的用户（含已停用用户），登录后列表含全部用户；多账号用户详情列出全部账号；无账号用户显示空态；不存在的用户显示「用户不存在」。（FR-014、FR-017 ~ FR-020、FR-031；quickstart S4）

### Tests for User Story 2 ⚠️

- [X] T072 [P] [US2] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/ListUsersIT.java`：默认每页 20、上限 100；按用户名或账号名搜索，大小写不敏感、包含匹配，去首尾空白后为空视为不搜索；`total` 与页码正确；`accountCount` 正确；用 `EXPLAIN` 断言搜索走 `pg_trgm` GIN 索引
- [X] T073 [P] [US2] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/GetUserIT.java`：多账号用户列出全部账号（账号标识、账号种类、状态、创建时间），无遗漏、不混入他人账号；无账号用户 `accounts` 为空数组；不存在 → 404 `user_not_found`；响应不含密码、哈希、凭据
- [X] T074 [P] [US2] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/UsersReadContractIT.java`：`GET /v1/users`、`GET /v1/users/{userId}` 按 OpenAPI 校验
- [X] T075 [P] [US2] 组件测试 `apps/admin-center/tests/unit/components/users/{user-list,user-search-box}.test.tsx`：列「用户名、状态、关联账号数、创建时间」；状态徽标；搜索；分页；空态「还没有用户」与无结果态；手机宽度变卡片
- [X] T076 [P] [US2] 组件测试 `apps/admin-center/tests/unit/components/users/{user-detail,account-list}.test.tsx`：基本信息与账号表；无账号空态「该用户暂无关联账号」；已停用用户提示；「用户不存在」
- [X] T077 [P] [US2] E2E `apps/admin-center/tests/e2e/us2-users.spec.ts`（S4）：列表、搜索（大小写任意）、翻页、多账号详情、无账号详情、不存在的用户；断言从任一页面到用户详情不超过 2 次点击 / 触控（SC-002），详情里的账号数与 UAM 实际一致（SC-003）

### Implementation for User Story 2

- [X] T078 [US2] 在 `apps/uam/src/main/java/com/s2u2m/uam/domain/port/UserRepository.java` 与 `apps/uam/src/main/java/com/s2u2m/uam/infrastructure/jdbc/JdbcUserRepository.java` 增加分页与搜索（`ILIKE '%q%'` 匹配 `app_user.name` 与 `account.login_name_norm`；`accountCount` 用 `account(user_id)` 索引聚合；`limit/offset`）
- [X] T079 [US2] 用例与 DTO `apps/uam/src/main/java/com/s2u2m/uam/application/{ListUsersUseCase,GetUserUseCase}.java`、`apps/uam/src/main/java/com/s2u2m/uam/api/dto/{UserSummary,UserPage,UserDetail,AccountView}.java`；使 T072、T073 通过
- [X] T080 [US2] `apps/uam/src/main/java/com/s2u2m/uam/api/UsersController.java`：`GET /v1/users`、`GET /v1/users/{userId}`；使 T074 通过
- [X] T081 [US2] 在 `apps/admin-center/src/lib/uam/client.ts` 增加 `listUsers`、`getUser`
- [X] T082 [US2] 用户列表页 `apps/admin-center/src/app/(shell)/users/page.tsx` 与 `apps/admin-center/src/components/users/{user-list,user-search-box}.tsx`；使 T075 通过
- [X] T083 [US2] 用户详情页 `apps/admin-center/src/app/(shell)/users/[userId]/page.tsx` 与 `apps/admin-center/src/components/users/{user-detail,account-list}.tsx`；使 T076 通过
- [X] T084 [US2] 验证：T072 ~ T077 全部通过（`apps/admin-center/tests/e2e/us2-users.spec.ts` 与 UAM 的 `*IT`）；跑 quickstart S4

**Checkpoint**: US1 与 US2 都可独立工作。

---

## Phase 5: User Story 3 - 在 Session Manager 中查看在线用户及其会话（Priority: P1）

**Goal**: 在线用户列表（至少有一个有效会话的用户）与某用户的当前会话（含「当前会话」标注）。依赖 US1（会话来自登录）。

**Independent Test**: 让两个用户登录（其中一个用两个账号），Session Manager 列表只含这两个在线用户；多账号用户有两条会话分别标明账号；会话结束后刷新即消失。（FR-014、FR-028 ~ FR-032；quickstart S7）

### Tests for User Story 3 ⚠️

- [X] T085 [P] [US3] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/OnlineUsersIT.java`：只含有有效会话的用户；`sessionCount` 与 `lastActiveAt` 正确，按最近活动倒序；已过期的会话不计入（`ZREMRANGEBYSCORE -inf now` 清理）；用户名回 PostgreSQL 批量取；分页
- [X] T086 [P] [US3] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/UserSessionsIT.java`：某用户的会话 = 其每个账号的 `uam:acct:{accountId}:sess` 指向的会话；每条含账号名与种类、登录 / 最近活动 / 到期时间；`current` 由请求头 `X-Operator-Session-Ref` 与会话引用相等得出；响应与日志中**没有会话标识**
- [X] T087 [P] [US3] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/SessionsReadContractIT.java`：`GET /v1/online-users`、`GET /v1/users/{userId}/sessions`
- [X] T088 [P] [US3] 组件测试 `apps/admin-center/tests/unit/components/sessions/{online-user-list,session-list}.test.tsx`：在线用户列「用户名、有效会话数、最近活动」；空态「当前没有在线用户」；会话卡片含账号与种类、时间、会话引用（不含会话标识）；「当前会话」标注
- [X] T089 [P] [US3] E2E `apps/admin-center/tests/e2e/us3-sessions.spec.ts`（S7）：断言到在线用户会话详情不超过 2 次点击 / 触控（SC-002），在线用户集合与实际有会话的用户一致（SC-003）

### Implementation for User Story 3

- [X] T090 [US3] 在 `apps/uam/src/main/java/com/s2u2m/uam/domain/port/SessionStore.java` 与 `RedisSessionStore.java` 增加 `listOnline`（清理后在 UAM 内按用户聚合）与 `sessionsOfAccounts`
- [X] T091 [US3] 用例与 DTO `apps/uam/src/main/java/com/s2u2m/uam/application/{ListOnlineUsersUseCase,ListUserSessionsUseCase}.java`、`apps/uam/src/main/java/com/s2u2m/uam/api/dto/{OnlineUser,OnlineUserPage,SessionView}.java`；使 T085、T086 通过
- [X] T092 [US3] `apps/uam/src/main/java/com/s2u2m/uam/api/SessionsController.java`：`GET /v1/online-users`、`GET /v1/users/{userId}/sessions`；使 T087 通过
- [X] T093 [US3] 在 `apps/admin-center/src/lib/uam/client.ts` 增加 `listOnlineUsers`、`listUserSessions`
- [X] T094 [US3] 页面 `apps/admin-center/src/app/(shell)/sessions/page.tsx`、`apps/admin-center/src/app/(shell)/sessions/[userId]/page.tsx` 与 `apps/admin-center/src/components/sessions/{online-user-list,session-list}.tsx`；使 T088 通过
- [X] T095 [US3] 验证：T085 ~ T089 通过（`apps/admin-center/tests/e2e/us3-sessions.spec.ts`）；跑 quickstart S7

**Checkpoint**: 三个 P1 故事（US1 ~ US3）完成，达到 MVP。

---

## Phase 6: User Story 4 - 在 User Manager 中创建「账号名 / 密码」用户（Priority: P2）

**Goal**: 新建用户（用户 + 一个账号 + 凭据同事务），含校验、账号名冲突、幂等、并发。登录后的详情页跳转依赖 US2。

**Independent Test**: 新建用户后进入其详情页；用该账号立即可登录；已存在账号名被拒；双击只建一个。（FR-021 ~ FR-023、FR-034；quickstart S5）

### Tests for User Story 4 ⚠️

- [X] T096 [P] [US4] 用例单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/application/CreateUserUseCaseTest.java`：用例只负责编排（字段规则校验由 `AccountProvisioningServiceTest` 覆盖）：服务返回的字段级错误 `errors[{field, reason}]` 原样透传；成功与失败（含被拒）都写 `uam.admin.write`（`action=user.create`）
- [X] T097 [P] [US4] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/CreateUserIT.java`：用户 + 账号 + 凭据在**同一事务**，失败不留半成品；账号名冲突（大小写不同、含已停用用户的账号）→ 409 `account_name_taken`；`Idempotency-Key` 相同的重放（10 分钟内）返回原 201；密码只以哈希落库，响应与日志里没有密码；`app_user.updated_at` 与 `credential.updated_at` 非空
- [X] T098 [P] [US4] 并发测试 `apps/uam/src/test/java/com/s2u2m/uam/concurrency/ConcurrentCreateUserIT.java`：两个请求并发抢同一账号名，只有一个成功；同键重复提交只建一个用户
- [X] T099 [P] [US4] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/UsersWriteContractIT.java`：`POST /v1/users` 的 201 / 400 `operator_required` / 409 / 422
- [X] T100 [P] [US4] 单元与组件测试 `apps/admin-center/tests/unit/app/new-user-actions.test.ts`、`apps/admin-center/tests/unit/components/users/user-form.test.tsx`：每次渲染生成 `Idempotency-Key`；字段旁显示原因；冲突保留已填内容（密码除外）；成功 303 到详情页并提示「用户已创建」；提交时按钮禁用
- [X] T101 [P] [US4] E2E `apps/admin-center/tests/e2e/us4-create-user.spec.ts`（S5）：创建后可登录（并记录创建耗时，SC-004：90% ≤ 2 分钟）；大小写不同的重名被拒；弱密码给出字段级原因；双击只建一个

### Implementation for User Story 4

- [X] T102 [US4] `apps/uam/src/main/java/com/s2u2m/uam/domain/port/IdempotencyStore.java` 与 `RedisGuards.java` 的幂等部分：键 `uam:idem:{key}`，TTL 10 分钟，`SET NX` 抢占，重放返回原响应
- [X] T103 [US4] `apps/uam/src/main/java/com/s2u2m/uam/application/CreateUserUseCase.java`（复用 `AccountProvisioningService`，只负责幂等键、写日志与编排）与 `apps/uam/src/main/java/com/s2u2m/uam/api/dto/CreateUserRequest.java`；使 T096 ~ T098 通过
- [X] T104 [US4] 在 `UsersController.java` 增加 `POST /v1/users`（读 `Idempotency-Key`，要求操作人请求头）；使 T099 通过
- [X] T105 [US4] 在 `apps/admin-center/src/lib/uam/client.ts` 增加 `createUser`
- [X] T106 [US4] 新建页 `apps/admin-center/src/app/(shell)/users/new/page.tsx`、`apps/admin-center/src/app/(shell)/users/new/actions.ts`、`apps/admin-center/src/components/users/user-form.tsx`；使 T100 通过
- [X] T107 [US4] 验证：T096 ~ T101 通过（`apps/admin-center/tests/e2e/us4-create-user.spec.ts`）；跑 quickstart S5

**Checkpoint**: US4 可独立验证。

---

## Phase 7: User Story 5 - 在 User Manager 中停用与启用用户（Priority: P2）

**Goal**: 停用（所有账号不能登录、所有会话立即结束）与启用（不恢复会话）；不能停用自己、不能停用最后一个正常用户；并发安全。依赖 US2（详情页）、US1（会话）。

**Independent Test**: 让用户 X 登录后停用：会话立即失效、X 下一次请求回登录页、X 登录被拒、从在线列表消失；启用后可重新登录且旧会话不恢复。（FR-016、FR-024、FR-025；quickstart S6）

### Tests for User Story 5 ⚠️

- [X] T108 [P] [US5] 单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/domain/SelfProtectionRuleTest.java`：不能停用操作人自己所属的用户（`cannot_disable_self`）；不能停用最后一个 `ACTIVE` 用户（`cannot_disable_last_active_user`）
- [X] T109 [P] [US5] 用例单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/application/DisableEnableUserUseCaseTest.java`：停用幂等、启用幂等；停用「提交后」才终止会话；写日志（`user.disable` / `user.enable`）
- [X] T110 [P] [US5] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/DisableUserIT.java`：停用后其全部账号的会话结束、登录被拒（统一提示）、从在线列表消失；启用不恢复会话；账号自身状态不变；`app_user.updated_at` 随状态变更前进
- [X] T111 [P] [US5] 并发测试 `apps/uam/src/test/java/com/s2u2m/uam/concurrency/ConcurrentDisableIT.java`：两个运维人员同时停用对方、或并发停用最后两个正常用户，始终至少留一个 `ACTIVE` 用户（`pg_advisory_xact_lock` 串行化）
- [X] T112 [P] [US5] 竞态测试 `apps/uam/src/test/java/com/s2u2m/uam/concurrency/DisableVsLoginRaceIT.java`：停用与登录两种交错顺序，都不会留下已停用用户的有效会话（登录写入会话后复查用户状态）
- [X] T113 [P] [US5] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/UserStatusContractIT.java`：`POST /v1/users/{userId}/disable`、`/enable` 的 200 / 400 / 404 / 409
- [X] T114 [P] [US5] 组件与动作测试 `apps/admin-center/tests/unit/components/users/status-actions.test.tsx`、`apps/admin-center/tests/unit/app/user-status-actions.test.ts`：停用 / 启用需二次确认（说明后果）；停用自己、最后一个正常用户时入口禁用并说明原因；UAM 不可用时提示「操作未生效」，不显示成功 （偏差：「最后一个正常用户」无法由客户端预知，UserDetail 不含活跃用户数，界面改为尝试后展示 UAM 的 409 `cannot_disable_last_active_user` 文案；若要预先禁用入口，需在 openapi 增加 `activeUserCount` 之类字段，待用户确认）
- [X] T115 [P] [US5] E2E `apps/admin-center/tests/e2e/us5-disable.spec.ts`（S6）：断言停用后该用户全部会话在任一实例的下一次请求都被视为未登录、登录被拒（SC-005）

### Implementation for User Story 5

- [X] T116 [US5] `apps/uam/src/main/java/com/s2u2m/uam/domain/policy/SelfProtectionRule.java`；使 T108 通过
- [X] T117 [US5] `apps/uam/src/main/java/com/s2u2m/uam/domain/port/UserStatusLock.java`、`UserRepository` 增加状态更新与 `countActive`，`JdbcUserRepository` 用 `pg_advisory_xact_lock` 实现锁；状态更新同时把 `app_user.updated_at` 设为 `now()`
- [X] T118 [US5] 在 `SessionStore` 与 `RedisSessionStore` 增加 `terminateAllForUser`（经该用户全部账号，用 Lua 同时清 `sess` / `sref` / `acct` 指针 / `online`，撤销后 `WAIT 1`）
- [X] T119 [US5] `apps/uam/src/main/java/com/s2u2m/uam/application/{DisableUserUseCase,EnableUserUseCase}.java`（事务内取锁并校验，**提交后**再终止会话；写日志）；使 T109 ~ T111 通过
- [X] T120 [US5] 修改 `apps/uam/src/main/java/com/s2u2m/uam/application/LoginUseCase.java`：写入会话后再读一次用户状态，若已 `DISABLED` 则终止刚建的会话并拒绝；使 T112 通过
- [X] T121 [US5] 在 `UsersController.java` 增加 `POST /v1/users/{userId}/disable` 与 `/enable`；使 T113 通过
- [X] T122 [US5] Admin Center：`apps/admin-center/src/lib/uam/client.ts` 增加 `disableUser`、`enableUser`；`apps/admin-center/src/app/(shell)/users/[userId]/actions.ts`；`apps/admin-center/src/components/users/status-actions.tsx`（`alert-dialog` 确认）；使 T114 通过
- [X] T123 [US5] 验证：T108 ~ T115 通过（`apps/admin-center/tests/e2e/us5-disable.spec.ts`）；跑 quickstart S6

**Checkpoint**: US5 可独立验证。

---

## Phase 8: User Story 6 - 在 Session Manager 中强制下线某个会话（Priority: P2）

**Goal**: 对某用户的某条会话强制下线，只终止该会话；不能下线自己的当前会话。依赖 US3。

**Independent Test**: 用户用账号 A、B 登录，强制下线 A 的会话：A 立即失效且下一次请求回登录页，B 不受影响；对自己的会话被拒；已结束的会话友好提示。（FR-028、FR-030、FR-016；quickstart S8）

### Tests for User Story 6 ⚠️

- [X] T124 [P] [US6] 用例单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/application/TerminateSessionUseCaseTest.java`：目标等于 `X-Operator-Session-Ref` → `cannot_terminate_own_session`；目标不存在 → `session_not_found`；成功与被拒都写日志（`session.terminate`）
- [X] T125 [P] [US6] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/TerminateSessionIT.java`：只终止目标会话，同用户其他会话、账号与用户状态不变；下线后可再次登录
- [X] T126 [P] [US6] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/SessionTerminateContractIT.java`：`DELETE /v1/sessions/{sessionRef}` 的 204 / 400 / 404 / 409
- [X] T127 [P] [US6] 组件与动作测试 `apps/admin-center/tests/unit/components/sessions/terminate-dialog.test.tsx`、`apps/admin-center/tests/unit/app/terminate-actions.test.ts`：二次确认；自己的当前会话入口禁用并提示改用「退出登录」；`session_not_found` 提示「该会话已结束」并刷新；被下线者登录页提示「你的会话已被管理员强制下线」 （偏差：UAM verify 的 401 不区分「被管理员强制下线」与到期 / 退出 / 被取代，登录页只能显示通用的「会话已结束」；若要显示「你的会话已被管理员强制下线」，需在 openapi 给 `session_invalid` 增加可选 `reason`，UAM 在 terminate.lua 写短期 tombstone，待用户确认）
- [X] T128 [P] [US6] E2E `apps/admin-center/tests/e2e/us6-terminate.spec.ts`（S8）：断言从在线用户列表出发不超过 4 次点击 / 触控（含确认）完成强制下线，同用户其他会话不受影响（SC-006）

### Implementation for User Story 6

- [X] T129 [US6] 在 `SessionStore` 与 `RedisSessionStore` 增加 `terminateByRef`（`uam:sref:{ref}` → 哈希 → Lua `apps/uam/src/main/resources/redis/terminate.lua`，`WAIT 1`）
- [X] T130 [US6] `apps/uam/src/main/java/com/s2u2m/uam/application/TerminateSessionUseCase.java`（`SelfProtectionRule`；写日志）；使 T124、T125 通过
- [X] T131 [US6] 在 `SessionsController.java` 增加 `DELETE /v1/sessions/{sessionRef}`；使 T126 通过
- [X] T132 [US6] Admin Center：`client.ts` 增加 `terminateSession`；`apps/admin-center/src/app/(shell)/sessions/[userId]/actions.ts`；`apps/admin-center/src/components/sessions/terminate-dialog.tsx`；登录页对「被强制下线」「会话已结束」的提示；使 T127 通过
- [X] T133 [US6] 验证：T124 ~ T128 通过（`apps/admin-center/tests/e2e/us6-terminate.spec.ts`）；跑 quickstart S8

**Checkpoint**: US6 可独立验证。

---

## Phase 9: User Story 7 - 已登录用户修改自己的密码（Priority: P2）

**Goal**: 从顶栏用户菜单修改当前账号的密码（需当前密码），当前会话保持有效，只改指定账号。依赖 US1。

**Independent Test**: 新建用户并登录，改密；退出后旧密码被拒、新密码可登录；当前密码错、新密码弱、与当前相同、两次不一致都给出明确原因。（FR-026、FR-027；quickstart S9）

### Tests for User Story 7 ⚠️

- [X] T134 [P] [US7] 用例单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/application/ChangePasswordUseCaseTest.java`：校验当前密码（`current_password_incorrect`）；新密码满足强度（`weak_password`）且不同于当前（`same_as_current`）；只改路径里的 `accountId`；写日志（`account.password.change`）
- [X] T135 [P] [US7] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/ChangePasswordIT.java`：成功后当前会话保持有效、同用户其他账号不受影响、旧密码不能登录；当前密码连续错误按 `uam:throttle:pwchg:{sha256(accountId|来源地址)}` 限制，口径同登录；日志与响应没有当前密码与新密码；`credential.updated_at` 随改密前进
- [X] T136 [P] [US7] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/AccountPasswordContractIT.java`：`POST /v1/accounts/{accountId}/password` 的 204 / 400 / 422 / 429
- [X] T137 [P] [US7] 单元与组件测试 `apps/admin-center/tests/unit/app/password-actions.test.ts`、`apps/admin-center/tests/unit/components/auth/change-password-form.test.tsx`：必填、两次输入一致、字段旁显示原因、不回填、`autocomplete` 为 `current-password` / `new-password`；成功提示「密码已修改」且当前会话保持有效；`throttled` 提示
- [X] T138 [P] [US7] E2E `apps/admin-center/tests/e2e/us7-password.spec.ts`（S9）：改密耗时记录（SC-007：90% ≤ 1 分钟），旧密码 100% 不能登录、新密码 100% 可登录

### Implementation for User Story 7

- [X] T139 [US7] `PasswordChangeThrottle` 端口与 `RedisGuards.java` 实现；`CredentialRepository` 增加 `updateHash`（同时把 `credential.updated_at` 设为 `now()`） （说明：`CredentialRepository.updatePasswordHash` 已在 US1 具备并会推进 `credential.updated_at`，本阶段复用，未新增 `updateHash`）
- [X] T140 [US7] `apps/uam/src/main/java/com/s2u2m/uam/application/ChangePasswordUseCase.java`；使 T134、T135 通过
- [X] T141 [US7] `apps/uam/src/main/java/com/s2u2m/uam/api/AccountsController.java`：`POST /v1/accounts/{accountId}/password`；使 T136 通过
- [X] T142 [US7] Admin Center：`client.ts` 增加 `changePassword`（`accountId` 取自操作人自己的账号）；`apps/admin-center/src/app/(shell)/account/password/{page,actions}.tsx`；`apps/admin-center/src/components/auth/change-password-form.tsx`；用户菜单入口；`apps/admin-center/src/lib/validation/password.ts`；使 T137 通过
- [X] T143 [US7] 验证：T134 ~ T138 通过（`apps/admin-center/tests/e2e/us7-password.spec.ts`）；跑 quickstart S9

**Checkpoint**: US7 可独立验证。

---

## Phase 10: User Story 8 - 退出登录与会话自然失效（Priority: P2）

**Goal**: 主动退出、空闲 / 绝对到期；会话结束后任一实例的下一次请求都被视为未登录。依赖 US1。

**Independent Test**: 登录后退出：cookie 被清、回登录页、旧会话标识再请求被视为未登录；空闲到期同样。（FR-009 ~ FR-011；quickstart S10）

### Tests for User Story 8 ⚠️

- [X] T144 [P] [US8] 测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/application/LogoutUseCaseTest.java` 与集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/SessionExpiryIT.java`（测试 profile 把空闲与绝对时限调到秒级）：退出幂等；空闲到期、绝对到期后 `verify` → `session_invalid`；到期会话不出现在在线用户里
- [X] T145 [P] [US8] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/AuthLogoutContractIT.java`：`POST /v1/auth/logout` 返回 204（会话不存在也一样）
- [X] T146 [P] [US8] 测试 `apps/admin-center/tests/unit/app/logout-route.test.ts`（`POST /logout`：调 UAM、清 cookie、回 `/login`；同源校验）与 `apps/admin-center/tests/unit/components/sessions/stale-session.test.tsx`（会话详情页停留期间该会话结束，刷新后显示最新状态与友好提示）
- [X] T147 [P] [US8] E2E `apps/admin-center/tests/e2e/us8-logout-expiry.spec.ts`（S10）：会话因退出、到期、被取代、被强制下线、停用结束后，用其会话标识发起的下一次请求 100% 被视为未登录，在线列表刷新后不再显示（SC-013）

### Implementation for User Story 8

- [X] T148 [US8] `apps/uam/src/main/java/com/s2u2m/uam/application/LogoutUseCase.java` 与 `AuthController.java` 增加 `POST /v1/auth/logout`；使 T144（退出部分）、T145 通过
- [X] T149 [US8] 让 `SessionExpiryIT` 通过：时限由 `uam.session.idle`、`uam.session.absolute` 配置，Redis TTL 取两者剩余的较小值，`online` 分数同步 （说明：空闲 / 绝对到期与 TTL 逻辑在 US1 已具备，本阶段以 SessionExpiryIT 固化并经变异检查证明非空转；E2E 栈的时限为 30 分钟 / 12 小时，到期只由该 IT 覆盖）
- [X] T150 [US8] `apps/admin-center/src/app/logout/route.ts` 与 `client.ts` 增加 `logout`；使 T146 通过
- [X] T151 [US8] 会话详情与用户详情在目标已结束时的友好提示与刷新（`apps/admin-center/src/components/sessions/session-list.tsx`） （说明：US6 的「该会话已结束」处理已满足，本阶段只补 stale-session 测试，无生产代码改动）
- [X] T152 [US8] 验证：T144 ~ T147 通过（`apps/admin-center/tests/e2e/us8-logout-expiry.spec.ts`）；跑 quickstart S10

**Checkpoint**: US8 可独立验证。

---

## Phase 11: User Story 9 - Admin Center 在桌面、平板、手机上自适应（Priority: P2）

**Goal**: 全部页面（含新建与改密表单、确认对话框）在 320 ~ 2560 宽度无整页横向滚动，导航随视口变化，触控与键盘可用。依赖 US1 ~ US8 的页面。

**Independent Test**: 六个视口宽度走完「登录 → 用户列表 → 新建 → 详情 → 停用 → 会话 → 强制下线 → 修改密码 → 退出」。（FR-035、FR-036；SC-008；quickstart S12）

### Verification for User Story 9 ⚠️

- [X] T153 [P] [US9] E2E `apps/admin-center/tests/e2e/us9-responsive.spec.ts`：在 `playwright.config.ts` 的六个视口 project 上走完整旅程；断言 `document.documentElement.scrollWidth <= innerWidth`；断言桌面侧边栏展开、平板图标条、手机抽屉；手机上列表为卡片
- [X] T154 [P] [US9] E2E `apps/admin-center/tests/e2e/us9-a11y.spec.ts`：`@axe-core/playwright` 无严重违规；仅键盘完成全部功能；对话框打开时焦点留在对话框内、关闭后回到触发元素；`Esc` 关闭
- [X] T155 [P] [US9] E2E `apps/admin-center/tests/e2e/us9-touch-and-content.spec.ts`：可交互元素的触控目标 ≥ 44 × 44 CSS 像素，破坏性按钮与相邻按钮留有间距；很长的用户名 / 账号名在窄屏换行或截断，不撑破布局
- [X] T156 [US9] 修复 T153 ~ T155 暴露的全部问题（`apps/admin-center/src/components/**`、`apps/admin-center/src/app/globals.css`），不改变行为；重跑直至通过 （说明：用户菜单改为 `modal={false}` 以消除 axe 的 aria-hidden-focus（serious）；唯一的行为差异是点击菜单外区域时既关闭菜单、也会点到下面的元素，待用户确认可接受）
- [X] T157 [US9] 验证：T153 ~ T155 在 `apps/admin-center/playwright.config.ts` 的六个视口 project 上全部通过；跑 quickstart S12

**Checkpoint**: US9 可独立验证。

---

## Phase 12: User Story 10 - 服务可以横向扩展，且不影响已登录用户（Priority: P2）

**Goal**: 多实例无会话粘滞、跨实例一致、滚动重启不登出、规模与吞吐达标。依赖 US1 ~ US8 的真实流程。

**Independent Test**: 3 个 Admin Center + 3 个 UAM 置于同一入口后，登录一次后请求分发到各实例都已登录；经一个实例强制下线，其他实例立即判无效；滚动重启 0 次意外登出。（FR-037 ~ FR-040；SC-009 ~ SC-012；quickstart S13 ~ S15）

### Tests / Verification for User Story 10 ⚠️

- [X] T158 [P] [US10] 测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/TwoInstanceConsistencyIT.java`：两个 UAM 实例共享 PostgreSQL 与 Redis，A 签发的会话 B 立即能 `verify`；A 终止后 B 的下一次 `verify` 即 `session_invalid`；A 新建的用户 B 立即可见并可登录
- [X] T159 [P] [US10] 测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/GracefulShutdownIT.java`：优雅停机期间在途请求完成、就绪探针先变为未就绪

### Implementation for User Story 10

- [X] T160 [P] [US10] `infra/local/docker-compose.scale.yml`：3 个 Admin Center + 3 个 UAM + nginx；UAM 不映射宿主机端口，只对 Admin Center 实例可达
- [X] T161 [P] [US10] `infra/local/nginx/nginx.conf`：轮询、**无会话粘滞**、到上游的长连接
- [X] T162 [P] [US10] `infra/local/scripts/scale-checks.sh`：登录一次后把请求分到全部 Admin Center 实例，断言都已登录；经一个实例新建用户 / 强制下线，断言其他实例立即一致
- [X] T163 [P] [US10] `infra/local/scripts/rolling-restart.sh`：持续访问下依次重启全部实例，统计意外登出数与失败请求数
- [X] T164 [P] [US10] `infra/load/k6/seed-data.sh`：生成 10 万用户（含账号与凭据）与 1 万个同时有效的会话
- [X] T165 [P] [US10] `infra/load/k6/list-and-detail.js`：用户与会话的列表和详情，阈值 95% ≤ 2 秒（SC-009）
- [X] T166 [P] [US10] `infra/load/k6/validate-throughput.js` 与方法说明 `infra/load/SC-011.md`：1 → 4 实例吞吐 ≥ 3.2 倍；独占机器、预热、多轮取中位数、判定口径
- [X] T167 [US10] 验证：T158、T159 通过；在多实例栈上跑 quickstart S13（SC-010）、S14（SC-012，滚动重启 0 次意外登出）、S15（SC-009、SC-011），结果记入 `infra/load/RESULTS.md` （结果见 `infra/load/RESULTS.md`：S13 31/31 通过、S14 滚动重启 0 次意外登出（最终配置 7 轮 44,306 个请求 0 失败）、SC-009 在 10 万用户 / 1 万会话下满足；**SC-011 未测量也未判定**（需要独占机器，本机只跑了脚本冒烟）；**发现**：`online.lua` 在 1 万会话时每次约 22 ms Redis 时间，在线用户列表约 45 次 / 秒即触顶（14.3% 页面触发 1.5 秒超时），提议改为只读 `uam:online` 的分数、由分数推出最近活动时间，待用户确认后再改）

**Checkpoint**: US10 可独立验证。

---

## Phase 13: User Story 11 - 评审并确认 Admin Center 界面设计原型（Priority: P2）

**Goal**: 原型已交付并经用户确认；实现与原型一致，且没有范围外的入口。原型见 [prototype/](./prototype/README.md)。

**Independent Test**: 实现后的每个原型页面与关键状态都能在真实界面里复现，且没有编辑、删除、重置他人密码、绑定账号、批量下线等入口。（FR-041；SC-015；quickstart S18）

- [ ] T168 [US11] 在 `specs/002-admin-center-uam/spec.md` 的 Clarifications 记录「技术栈与界面设计原型已确认（2026-10-23）」，并在 `specs/002-admin-center-uam/prototype/README.md` 标注状态为已确认
- [ ] T169 [P] [US11] E2E `apps/admin-center/tests/e2e/us11-prototype-states.spec.ts`：逐条覆盖原型 README 的关键状态——登录失败与限流、空列表、用户无账号、无在线会话、会话已失效与被强制下线的提示、UAM 暂不可用（停掉 UAM 或配错密钥）、新建与改密的表单校验与账号名冲突、停用与强制下线的确认、不可停用 / 不可下线的禁用说明
- [ ] T170 [P] [US11] E2E `apps/admin-center/tests/e2e/us11-no-out-of-scope.spec.ts`：遍历所有页面，断言**不存在**编辑用户名、删除用户、重置他人密码、找回密码、绑定账号、批量或一键下线的入口
- [ ] T171 [US11] 验证：T169、T170 通过；对照 `specs/002-admin-center-uam/prototype/README.md` 的「覆盖的页面与状态」表逐项核对

**Checkpoint**: US11 完成。

---

## Phase 14: User Story 12 - UAM 只接受持有服务密钥的受信任客户端（Priority: P2）

**Goal**: 在基础阶段的识别与 401 之上，补全权限（403）、双密钥不停机轮换、拒绝日志、密钥生成工具与 Admin Center 的配置错误处理。

**Independent Test**: 用生成工具生成并配置密钥；不带密钥、带错密钥直接调 UAM 任一接口被拒；Admin Center 带正确密钥时全部功能正常；做一次轮换，期间用户无感。（FR-008、FR-042 ~ FR-044；SC-016；quickstart S16、S19）

### Tests for User Story 12 ⚠️

- [ ] T172 [P] [US12] 单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/ClientRegistryRotationTest.java`：同一客户端至多两个有效哈希；撤销其一后旧密钥被拒、新密钥有效；三个哈希的配置被拒绝启动
- [ ] T173 [P] [US12] 契约测试 `apps/uam/src/test/java/com/s2u2m/uam/contract/ClientAuthContractIT.java`：遍历 `contracts/uam/openapi.yaml` 的**每个**接口——不带密钥、错密钥、已撤销密钥 → 401 `client_unauthenticated` 且响应不透露哪里不对；密钥有效但没有 `assert-end-user-context` 权限的客户端调用登录、校验会话、退出、修改密码与所有写操作 → 403 `client_forbidden`，只读查询仍 200
- [ ] T174 [P] [US12] 集成测试 `apps/uam/src/test/java/com/s2u2m/uam/integration/RejectedCallLogIT.java`：被拒绝的调用写结构化日志（来源、时间、原因类别），**永不含**密钥
- [ ] T175 [P] [US12] 单元测试 `apps/uam/src/test/java/com/s2u2m/uam/unit/cli/GenerateServiceKeyCommandTest.java`：密钥为 32 字节密码学安全随机数、base64url、前缀 `uamk_`；标准输出只输出密钥一次；标准错误输出对应的 `sha256:<hex>` 与 `UAM_CLIENTS` 配置片段；不连库、不连 Redis
- [ ] T176 [P] [US12] 单元测试 `apps/admin-center/tests/unit/lib/session/misconfig.test.ts`：UAM 返回 401 `client_unauthenticated` / 403 `client_forbidden` 时按 `unverified` 处理——不登出用户、**不清 cookie**、显示「服务暂不可用」，并写运维告警日志（不含密钥）；写操作提示「操作未生效」
- [ ] T177 [P] [US12] E2E `apps/admin-center/tests/e2e/us12-rotation.spec.ts`（S19）：持续访问下完成一次密钥轮换，0 个用户被登出、0 个请求因密钥失败；把 Admin Center 的密钥故意配错，已登录用户不被登出，改回后无需重新登录

### Implementation for User Story 12

- [ ] T178 [US12] 在 `ClientRegistry.java` 与 `ClientAuthFilter.java` 补全权限 `assert-end-user-context` 与 403 `client_forbidden`；使 T173（权限部分）通过
- [ ] T179 [US12] 在 `ClientRegistry.java` 支持至多两个同时有效的密钥哈希并校验数量；使 T172 通过
- [ ] T180 [US12] 在 `ClientAuthFilter.java` 写拒绝日志（来源、时间、原因类别，不含密钥）；使 T174 通过
- [ ] T181 [US12] 部署期命令 `apps/uam/src/main/java/com/s2u2m/uam/cli/GenerateServiceKeyCommand.java`；使 T175 通过
- [ ] T182 [US12] Admin Center：在 `apps/admin-center/src/lib/uam/errors.ts` 与 `apps/admin-center/src/lib/logging.ts` 实现 `misconfigured` → `unverified` 与告警日志；使 T176 通过
- [ ] T183 [US12] `infra/local/scripts/rotate-key.sh`：新增第二个哈希 → 滚动发布 UAM → Admin Center 换新密钥 → 删旧哈希；`infra/local/scripts/bootstrap.sh` 改用 `generate-service-key`
- [ ] T184 [US12] 验证：T172 ~ T177 通过（含 `infra/local/scripts/rotate-key.sh` 的轮换演练）；跑 quickstart S16、S19

**Checkpoint**: 全部 12 个用户故事完成。

---

## Phase 15: Polish & Cross-Cutting Concerns

**Purpose**: 跨故事的收尾、门禁与归档

- [ ] T185 [P] 安全检查脚本 `infra/local/scripts/security-check.sh`：没有被跟踪的 `.env`；`apps/admin-center/package.json` 不含数据库驱动或 ORM 依赖（FR-013：Admin Center 不持久化数据）；仓库与历史里没有 `uamk_` 密钥或私钥特征（`infra/local/e2e.env` 里标注为仅测试用的固定密钥除外）；`apps/admin-center/src/lib` 的服务端模块都有 `import "server-only"`；浏览器包（`.next/static`）里没有服务密钥；运行期响应与 UAM、Admin Center 日志里没有密码、密码哈希、会话标识、服务密钥（SC-014）；跨站伪造的写请求 100% 被拒
- [ ] T186 [P] 覆盖率门禁核对：`mvn -f apps/uam/pom.xml verify` 行覆盖率 ≥ 90%；`npm --prefix apps/admin-center run test:cov` ≥ 90%；排除路径只含不含业务逻辑的代码，并写进两个 `AGENTS.md`
- [ ] T187 [P] 补充 `apps/uam/AGENTS.md` 与 `apps/admin-center/AGENTS.md` 的最终命令、环境变量与排障说明（含 `UAM_CLIENTS` 示例、`generate-service-key`、`init-admin`、`add-account`、轮换步骤）
- [ ] T188 对触及的两个应用（`apps/uam/pom.xml`、`apps/admin-center/package.json`）执行质量门禁：UAM 的 `spotless:check`、`compile`、`verify`；Admin Center 的 `lint`、`typecheck`、`test:cov`、`build`；全部通过
- [ ] T189 按 `specs/002-admin-center-uam/quickstart.md` 完整跑一遍 S1 ~ S19，把结果与未达标项写入归档文档的「验证记录」
- [ ] T190 [P] 归档需求与领域现状到 `docs/achieve-specs/admin-center-uam.md`（统一语言、领域模型、核心流程、需求对照表、验证记录、已知限制）
- [ ] T191 [P] 归档架构到 `docs/system-design/admin-center-uam.md`：把 `specs/002-admin-center-uam/diagrams/` 用 `git mv` 迁到 `docs/system-design/diagrams/`，修正文档中的图片与源文件链接
- [ ] T192 在 `specs/002-admin-center-uam/{spec,plan}.md` 顶部加「归档状态」说明，指向 `docs/achieve-specs/` 与 `docs/system-design/`；更新根 `README.md` 的项目入口
- [ ] T193 最终评审：对 `apps/`、`contracts/`、`infra/` 下本特性的全部改动运行代码评审与安全评审，修复发现的问题后重跑 T188

---

## Requirement Traceability（需求 → 任务）

| 需求 | 主要任务 |
|---|---|
| FR-001 ~ FR-003（登录、多账号识别、cookie） | T052、T055、T066、T068、T069、T039 |
| FR-004（账号与会话一对一） | T048、T049、T062 |
| FR-005、FR-006（统一失败提示、失败限制） | T045、T046、T050、T051、T053、T063 |
| FR-007（`verify` 返回会话数据） | T047、T050、T064 |
| FR-008（UAM 不基于用户会话、操作人以请求头传入） | T018、T019、T025、T026、T173 |
| FR-009（会话无效 / UAM 不可用的处理） | T032、T033、T039、T040、T176 |
| FR-010、FR-011（退出、到期、结束后任一实例视为未登录） | T144 ~ T150、T158、T162 |
| FR-012（登录后回到原页，站内路径） | T053、T067、T070 |
| FR-013（UAM 是权威，Admin Center 不持久化） | T015、T185 |
| FR-014、FR-015（UAM 的查询与管理能力） | T072 ~ T074、T085 ~ T087、T099、T113、T126、T136 |
| FR-016（停用或删除使会话失效） | T110、T112、T118、T119 |
| FR-017 ~ FR-020（User Manager 列表、搜索、详情） | T072 ~ T083 |
| FR-021 ~ FR-023（新建用户、校验、凭据保护） | T052、T064、T096 ~ T106 |
| FR-024、FR-025（停用 / 启用、自我保护） | T108 ~ T123 |
| FR-026、FR-027（修改自己的密码） | T134 ~ T142 |
| FR-028 ~ FR-032（Session Manager、强制下线、不泄露标识） | T085 ~ T094、T124 ~ T132 |
| FR-033（写操作防跨站伪造与明确反馈） | T034、T041、T114、T185 |
| FR-034（写操作日志） | T020、T027、T096、T109、T124、T134 |
| FR-035、FR-036（自适应、触控与键盘） | T036、T043、T153 ~ T157 |
| FR-037 ~ FR-040（横向扩展、一致、不登出） | T158 ~ T167 |
| FR-041（原型） | T168 ~ T171 |
| FR-042 ~ FR-044（服务密钥、权限、轮换） | T017、T018、T025、T172 ~ T184 |
| SC-001 ~ SC-007 | T055、T077、T089、T101、T115、T128、T138 |
| SC-008（六个视口） | T153 |
| SC-009、SC-011（规模与吞吐） | T165、T166 |
| SC-010、SC-012（多实例、滚动重启） | T162、T163 |
| SC-013（会话结束后视为未登录） | T147、T158 |
| SC-014（不泄露、防伪造） | T185 |
| SC-015（原型一致） | T169、T170 |
| SC-016（服务密钥） | T173、T177 |

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（Phase 1）**：无依赖，可立即开始
- **Foundational（Phase 2）**：依赖 Setup 完成，**阻塞**所有用户故事
- **User Stories（Phase 3 起）**：都依赖 Foundational 完成
- **Polish（Phase 15）**：依赖所需的用户故事全部完成

### User Story Dependencies

| 故事 | 优先级 | 依赖 | 说明 |
|---|---|---|---|
| US1 登录 | P1 | Foundational | MVP 起点；`init-admin`、`add-account` 命令在这里 |
| US2 用户列表与详情 | P1 | US1 | 登录后才能访问界面；UAM 一侧只依赖 Foundational |
| US3 在线用户与会话 | P1 | US1 | 会话来自登录 |
| US4 创建用户 | P2 | US1、US2 | 成功后跳转到 US2 的详情页 |
| US5 停用 / 启用 | P2 | US1、US2 | 详情页入口；停用会终止会话 |
| US6 强制下线 | P2 | US1、US3 | 会话详情页入口 |
| US7 修改密码 | P2 | US1 | |
| US8 退出与到期 | P2 | US1 | 会话到期语义在 US1 的 Redis 结构上验证 |
| US9 自适应 | P2 | US1 ~ US8 的页面 | 验证与修复 |
| US10 横向扩展 | P2 | US1 ~ US8 | 用真实流程验证一致性与吞吐 |
| US11 原型 | P2 | US2 ~ US8 的界面 | 一致性验证与范围外入口检查 |
| US12 服务密钥 | P2 | Foundational、US1 | 识别与 401 在基础阶段；权限、轮换、工具在本阶段 |

### Within Each User Story

- 测试任务**先写、先失败**，再做实现
- 领域模型 → 端口 → 基础设施 → 用例 → 控制器 → Admin Center 客户端 → 页面
- 故事完成并通过其「验证」任务后，再进入下一个

### Parallel Opportunities

- Setup 中标 [P] 的任务可并行；Foundational 中 UAM 与 Admin Center 两条线可并行（同一条线内按「测试 → 实现」顺序）
- Foundational 完成后，US1 与 US12 的测试编写可并行；US1 完成后，US2 / US3 / US7 / US8 可由不同的人并行；UAM 与 Admin Center 两侧也可分开并行（契约已定）
- 同一故事内标 [P] 的测试任务可并行；不同文件的实体、策略可并行

---

## Parallel Example: User Story 1

```text
# 并行启动 US1 的全部测试（它们都应先失败）：
Task: "领域策略单元测试 apps/uam/src/test/java/com/s2u2m/uam/unit/domain/..."            (T045)
Task: "LoginUseCaseTest apps/uam/src/test/java/com/s2u2m/uam/unit/application/..."       (T046)
Task: "RedisSessionStoreIT apps/uam/src/test/java/com/s2u2m/uam/integration/..."         (T048)
Task: "ConcurrentLoginIT apps/uam/src/test/java/com/s2u2m/uam/concurrency/..."           (T049)
Task: "login-actions 单元测试 apps/admin-center/tests/unit/app/login-actions.test.ts"    (T053)

# 并行实现不同文件：
Task: "值对象与枚举 apps/uam/src/main/java/com/s2u2m/uam/domain/model/..."               (T056)
Task: "策略 apps/uam/src/main/java/com/s2u2m/uam/domain/policy/..."                      (T058)
Task: "Argon2PasswordHasher apps/uam/src/main/java/.../infrastructure/security/..."      (T061)
Task: "登录表单校验与 next 白名单 apps/admin-center/src/lib/..."                          (T067)
```

---

## Implementation Strategy

### MVP First（US1 ~ US3）

1. 完成 Phase 1：Setup
2. 完成 Phase 2：Foundational（**关键**，阻塞所有故事）
3. 完成 Phase 3 ~ 5：US1、US2、US3（三个 P1 故事合起来才是可用的最小版本：能登录、能看用户、能看在线会话）
4. **停下并验证**：独立验证每个故事，跑 quickstart S1 ~ S4、S7
5. 具备条件即可演示

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. US1 → 独立验证 → 演示
3. US2、US3 → 独立验证 → 演示（MVP）
4. US4 ~ US8（写操作与会话生命周期）→ 逐个独立验证
5. US12（服务密钥）建议**在首次上线之前**完成
6. US9、US10、US11 → 自适应、扩展与原型一致性验证
7. Polish → 归档

### Parallel Team Strategy

多人协作时：

1. 团队一起完成 Setup + Foundational
2. 之后：A 负责 UAM 一侧（领域、用例、控制器），B 负责 Admin Center 一侧（页面、动作、UamClient），契约（`contracts/uam/openapi.yaml`）先行，双方都针对它写测试
3. 各故事独立完成并集成

---

## Notes

- [P] = 不同文件、无依赖
- [Story] 标签用于追溯到具体用户故事
- 每个故事应能独立完成与验证；**先看到测试失败，再实现**
- 并发规则（一账号一会话、最后一个正常用户、停用与登录竞态、并发建同名用户）必须有**确定性的**并发测试
- 每完成一个任务或一组任务就提交；在任何 Checkpoint 都可以停下来验证
- 契约变更须先改 `contracts/uam/openapi.yaml` 与失败用例，再改实现与客户端（根 `AGENTS.md`）
- 避免：含糊的任务、同文件冲突、破坏故事独立性的跨故事依赖
