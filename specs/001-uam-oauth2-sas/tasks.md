---

description: "auth（OAuth 2.0 / OIDC 授权服务器）实现任务清单"
---

# Tasks: auth

**Input**: `specs/001-uam-oauth2-sas/` 下的 spec.md、plan.md、research.md、data-model.md、contracts/、quickstart.md

**Prerequisites**: plan.md、spec.md（均已就绪）

**Tests**: **必须先写**。根 `AGENTS.md` 的「测试先行」要求：每个行为先有会失败的测试（red），再写实现（green），再重构。每个 user story 阶段内，测试任务排在实现任务之前，且实现开始前必须先运行并确认这些测试失败。

**Organization**: 按 user story 分组，每个阶段是可独立验证的增量。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成的依赖）
- **[Story]**: 所属 user story（US1–US6）
- 描述中给出完整相对路径

## Path Conventions

单模块 Maven 项目，位于仓库根的 `auth/`（plan.md「Project Structure」）。Java 基础包 `tech.toolkit.auth`：

- 主代码：`auth/src/main/java/tech/toolkit/auth/<package>/`
- 资源：`auth/src/main/resources/`
- 测试：`auth/src/test/java/tech/toolkit/auth/<package>/`（`*Test` 单元，`*IT` 集成与契约，均在 `mvn test` 中运行）

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 项目初始化与质量门禁

- [X] T001 核实 Spring Boot 4.1 BOM 下 Spring Authorization Server starter 的实际坐标与版本（官方文档对 `spring-boot-starter-oauth2-authorization-server` 与带 `security-` 前缀的名称说法不一），把结论写回 `specs/001-uam-oauth2-sas/research.md` 的 D1
- [X] T002 创建 `auth/pom.xml`：Spring Boot 4.1.x 父工程、Java 21、依赖（T001 的 starter、Spring Session JDBC、Flyway、PostgreSQL 驱动、BouncyCastle、Thymeleaf、Testcontainers 2、ArchUnit）、JaCoCo（行覆盖率 BUNDLE ≥ 90%，仅排除 `**/AuthApplication.class`）、Spotless（google-java-format）
- [X] T003 [P] 创建 `auth/.env.example`（数据库 owner 与 app 两套连接、`AUTH_ISSUER`、签名密钥、client secret）（另含 `AUTH_ADMIN_REDIRECT_URIS`：`auth-admin` client 的回环回调地址，逗号分隔）与 `auth/docker-compose.yml`（PostgreSQL 18）
- [X] T004 [P] 创建 `auth/AGENTS.md` 与 `auth/CLAUDE.md`（内容 `@AGENTS.md`）：技术栈与目录、dev / lint / typecheck / test / build 命令、覆盖率口径（行覆盖率 ≥ 90%）与排除路径、环境变量、密钥轮换步骤（根 AGENTS.md「子项目文档约定」）
- [X] T005 创建 `auth/src/main/java/tech/toolkit/auth/AuthApplication.java` 与 `auth/src/main/resources/application.yml`（profiles：`dev`、`bootstrap`；默认 profile 不允许临时密钥；`spring.session.jdbc.cleanup-cron` 设为每 10 分钟，由 Spring Session 清理过期会话）
- [X] T006 [P] 创建 `auth/src/test/java/tech/toolkit/auth/support/PostgresTestBase.java`（Testcontainers PostgreSQL、执行 Flyway 迁移、每个测试前清库）
- [X] T007 [P] 创建 `auth/src/test/java/tech/toolkit/auth/architecture/ArchitectureTest.java`（ArchUnit：package 无环、禁用字段注入与标准流、含密钥字段的类必须自带非 final `toString()`）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有 user story 共用的基础设施；**完成前不得开始任何 user story**

- [X] T008 创建 `auth/src/main/resources/db/migration/V1__auth_user.sql`：表 `auth_user`，`id bigint identity` 主键；`username varchar(64)` 唯一、小写存储、字符集 `[a-z0-9._-]`、长度 3–64；`password_hash varchar(255)`；`status varchar(16)` 为 `ENABLED` / `DISABLED`；`is_admin boolean` 默认 false；`must_change_password boolean` 默认 false；`failed_attempts int` 默认 0；`locked_until timestamptz` 可空；`password_changed_at timestamptz`；`created_at` / `updated_at timestamptz`
- [X] T009 创建 `auth/src/main/resources/db/migration/V2__oauth2_session_and_client_ext.sql`：SAS 标准表 `oauth2_registered_client`、`oauth2_authorization`；Spring Session JDBC 的 `spring_session` / `spring_session_attributes`；自有表 `auth_client_ext`（`client_id varchar(100)` 主键、`enabled boolean` 默认 true、`backchannel_logout_uri varchar(2000)` 可空、`post_logout_redirect_uris text`、`access_token_ttl_seconds int` 与 `refresh_token_ttl_seconds int` 可空）、`auth_sso_client`（主键 `(session_id varchar(36), client_id varchar(100))`、`first_seen_at timestamptz`）、`auth_logout_delivery`（`status varchar(16)` 为 `PENDING` / `DELIVERED` / `GAVE_UP`、`attempts int`、`next_attempt_at timestamptz`）
- [X] T010 创建 `auth/src/main/resources/db/migration/V3__audit_event.sql`：表 `auth_audit_event`（`type varchar(48)`、`actor varchar(100)` 可空、`subject_user_id bigint` 可空、`client_id varchar(100)` 可空、`source_ip varchar(64)`、`result varchar(16)` 为 `SUCCESS` / `FAILURE`、`detail jsonb`），应用数据库角色只授予 INSERT 与 SELECT
- [X] T011 配置 Flyway 用 owner 连接、应用运行用受限 app 连接：在 `auth/src/main/resources/application.yml` 分开配置 `spring.flyway.*` 与 `spring.datasource.*` 即可，不需要自定义类
- [X] T012 [P] 先写失败测试 `auth/src/test/java/tech/toolkit/auth/audit/AuditServiceIT.java`：写入事件、字段完整、应用角色不能 UPDATE / DELETE、`detail` 中出现密码或令牌原文时被拒绝
- [X] T013 实现 `auth/src/main/java/tech/toolkit/auth/audit/AuditEvent.java`、`AuditType.java`（`LOGIN_SUCCESS`、`LOGIN_FAILURE`、`ACCOUNT_LOCKED`、`LOGOUT`、`TOKEN_ISSUED`、`TOKEN_REFRESHED`、`TOKEN_REVOKED`、`REFRESH_REUSE_DETECTED`、`USER_CREATED`、`USER_UPDATED`、`PASSWORD_CHANGED`、`CLIENT_CREATED`、`CLIENT_UPDATED`、`SESSION_REVOKED`）与 `AuditService.java`（同时输出结构化日志）
- [X] T014 [P] 先写失败测试 `auth/src/test/java/tech/toolkit/auth/oauth/SigningKeyProviderTest.java`：从环境变量或挂载文件加载多把 ES256 PEM（1 把当前签名 + N 把仅验证）；`dev` profile 才允许临时密钥；非 dev 缺少密钥时启动失败；私钥不出现在 `toString()` 与日志
- [X] T015 实现 `auth/src/main/java/tech/toolkit/auth/oauth/SigningKeyProvider.java`
- [X] T016 [P] 先写失败测试 `auth/src/test/java/tech/toolkit/auth/oauth/JwkSourceConfigTest.java`：JWKS 只含公钥，包含当前与仅验证的全部密钥，私钥不出现在任何输出中
- [X] T017 `auth/src/main/java/tech/toolkit/auth/oauth/JwkSourceConfig.java`：由 `SigningKeyProvider` 提供多把密钥，只发布公钥（US1 签发令牌与 US6 发布 JWKS 共用）
- [X] T018 [P] 先写失败测试 `auth/src/test/java/tech/toolkit/auth/oauth/AuthorizationRevocationServiceIT.java`：按用户、按 client、按会话删除授权，只删目标，不影响其他
- [X] T019 `auth/src/main/java/tech/toolkit/auth/oauth/AuthorizationRevocationService.java`：按 `principal_name`、`client_id` 或会话删除授权（US2 退出、US3 吊销、US4 管理操作、US5 停用共用）

**Checkpoint**: 数据库、审计、签名密钥、JWK 源、吊销服务就绪，可以开始 user story。

---

## Phase 3: User Story 1 - 应用通过统一登录接入并完成登录 (Priority: P1) 🎯 MVP

**Goal**: 授权码 + PKCE 全流程可用：授权请求、登录页、授权码、换令牌、UserInfo（FR-001、FR-001a、FR-002、FR-005–FR-010、FR-030、FR-031）

**Independent Test**: 用示例 client 和测试用户走完“authorize → 登录 → 回调带 code → token → userinfo”（quickstart 场景 1–3）

### Tests for User Story 1（先写，并确认失败）

- [X] T020 [P] [US1] `auth/src/test/java/tech/toolkit/auth/oauth/AuthorizationCodeFlowIT.java`：完整授权码 + PKCE（S256）流程；重定向响应中不含令牌；授权码有效期 60 秒、一次性、绑定 client 与回调地址
- [X] T021 [P] [US1] `auth/src/test/java/tech/toolkit/auth/oauth/AuthorizationRequestValidationIT.java`：未登记 `client_id` 或与登记不符的 `redirect_uri` 显示错误页且不重定向；缺 `code_challenge` / `state`、`code_challenge_method` 非 `S256`、`response_type` 非 `code`、回调地址仅大小写或末尾斜杠不同、scope 超出登记范围，均按 contracts/oauth2-endpoints.md 处理；不出现同意页
- [X] T022 [P] [US1] `auth/src/test/java/tech/toolkit/auth/account/LoginFailureIT.java`：错误密码、不存在的用户名、禁用账号，返回逐字完全相同的提示（锁定账号的同类断言在 US4 的 LockoutIT）；登录页首次打开和每次失败后都含固定提示“多次失败后账号将被临时锁定”；登录页已展示后账号被禁用，提交时登录不得完成；用户不存在时也执行等耗时的哈希比对
- [X] T023 [P] [US1] `auth/src/test/java/tech/toolkit/auth/oauth/TokenClaimsIT.java`：访问令牌头部 `typ` 为 `at+jwt`，含 `iss`、`sub`、`aud`、`client_id`、`scope`、`sid`、`iat`、`exp`、`jti`，默认有效期 10 分钟；ID Token 含 `auth_time`、`nonce`、`sid`、`azp`；令牌不含密码、邮箱、手机号、角色清单；`/userinfo` 只返回 `sub` 与 `preferred_username`；`sid` 取当前登录会话 id，US2 切换为 Spring Session JDBC 后语义不变
- [X] T024 [P] [US1] `auth/src/test/java/tech/toolkit/auth/oauth/AuthorizationCodeReuseIT.java`：授权码被重复使用或被其他 client 使用时拒绝，并删除该授权码已换出的全部令牌
- [X] T025 [P] [US1] `auth/src/test/java/tech/toolkit/auth/oauth/LoginRequestExpiryIT.java`：登录请求自登录页展示起默认 10 分钟（`auth.login.request-ttl-seconds`）后提交，不处理凭据、显示“登录请求已过期”页；原授权请求仍保存时页面带重新发起的入口，会话已丢失时只提示回到应用重新登录；未超时正常登录（FR-002a）

### Implementation for User Story 1

- [X] T026 [P] [US1] `auth/src/main/java/tech/toolkit/auth/account/User.java` 与 `UserRepository.java`（按 T008 的列，用户名小写后查询）
- [X] T027 [P] [US1] `auth/src/main/java/tech/toolkit/auth/account/PasswordConfig.java`：argon2id（`Argon2PasswordEncoder`，BouncyCastle）
- [X] T028 [US1] `auth/src/main/java/tech/toolkit/auth/account/AuthUserDetailsService.java`：用户不存在时对固定哈希做一次等耗时比对；禁用与不存在对外表现一致
- [X] T029 [US1] `auth/src/main/java/tech/toolkit/auth/oauth/AuthorizationServerConfig.java`：仅 `authorization_code` 与 `refresh_token`、强制 PKCE S256、必须 `state`、回调精确匹配、不要求同意页、JDBC 的 client 与授权存储、issuer 取自配置、授权码有效期 60 秒
- [X] T030 [US1] `auth/src/main/java/tech/toolkit/auth/oauth/LoginSecurityConfig.java`、`auth/src/main/resources/templates/login.html`、`error.html`：表单登录（`username`、`password`）、统一失败提示“用户名或密码错误”、登录页常驻提示“多次失败后账号将被临时锁定”、授权请求错误页（不重定向）
- [X] T031 [US1] `auth/src/main/java/tech/toolkit/auth/oauth/LoginRequestExpiryFilter.java` 与 `auth/src/main/resources/templates/login-expired.html`：按上述规则判断登录请求是否过期，有效期取配置 `auth.login.request-ttl-seconds`，默认 600 秒
- [X] T032 [US1] `auth/src/main/java/tech/toolkit/auth/oauth/TokenCustomizer.java`：`typ: at+jwt`、`client_id`、`sid`、`scope`，不放个人资料；默认访问令牌 10 分钟
- [X] T033 [US1] `auth/src/main/java/tech/toolkit/auth/oauth/UserInfoMapper.java`：只返回 `sub` 与 `preferred_username`
- [X] T034 [US1] `auth/src/main/java/tech/toolkit/auth/oauth/AuthorizationCodeReuseHandler.java`：授权码复用时删除其已签发的令牌并写审计
- [X] T035 [US1] `auth/src/main/java/tech/toolkit/auth/audit/AuthAuditListener.java`：写 `LOGIN_SUCCESS`、`LOGIN_FAILURE`、`TOKEN_ISSUED`
- [X] T036 [P] [US1] `auth/src/main/java/tech/toolkit/auth/client/DevClientSeeder.java`：仅 `dev` 与测试 profile 登记示例 client1、client2（机密、授权码 + PKCE、回调地址）与一个测试用户

**Checkpoint**: US1 可独立运行与演示（MVP）。

---

## Phase 4: User Story 2 - 跨应用单点登录与单点退出 (Priority: P1)

**Goal**: SSO、强制重新认证、统一会话有效期、退出与 back-channel 通知（FR-003、FR-004、FR-014、FR-014a）

**Independent Test**: 两个 client 先后授权，第二个免输入；其中一个退出后，另一个收到通知并需要重新登录（quickstart 场景 4–5）

### Tests for User Story 2（先写，并确认失败）

- [X] T037 [P] [US2] `auth/src/test/java/tech/toolkit/auth/session/SsoSessionIT.java`：登录 client1 后 client2 授权免输入，且 3 秒内返回授权码（SC-002，宽松的单次延迟断言）；`prompt=login` 与超过 `max_age` 时强制重新认证
- [X] T038 [P] [US2] `auth/src/test/java/tech/toolkit/auth/session/SessionLifetimeIT.java`：空闲 30 分钟、绝对 8 小时到期后要求重新登录，两个值可配置（用可控时钟）
- [X] T039 [P] [US2] `auth/src/test/java/tech/toolkit/auth/logout/LogoutIT.java`：`/connect/logout` 结束 SSO 会话、删除该会话下全部授权、`post_logout_redirect_uri` 精确匹配、无该参数时显示“已退出”页
- [X] T040 [P] [US2] `auth/src/test/java/tech/toolkit/auth/logout/BackChannelLogoutIT.java`：`logout_token` 含 `iss`、`aud`、`iat`、`jti`、`sid`、`events` 且无 `nonce`、ES256 签名；5 秒超时；按 1s、5s、30s、2min、10min 退避重试共 5 次，记 `DELIVERED` 或 `GAVE_UP`；未登记 `backchannel_logout_uri` 的 client 不被通知；10 秒内送达（SC-009）；多实例不重复投递

### Implementation for User Story 2

- [X] T041 [US2] `auth/src/main/java/tech/toolkit/auth/session/SessionConfig.java` 与 `AbsoluteTimeoutFilter.java`：Spring Session JDBC（`sid` 语义与 US1 一致），空闲 30 分钟，绝对 8 小时按 `creationTime` 校验
- [X] T042 [P] [US2] `auth/src/main/java/tech/toolkit/auth/session/SsoClientRepository.java` 与 `SsoClientTracker.java`：授权时记录会话与 client 的对应（`auth_sso_client`）
- [X] T043 [P] [US2] `auth/src/main/java/tech/toolkit/auth/client/ClientExtRepository.java`：读写 `auth_client_ext`（`backchannel_logout_uri`、`post_logout_redirect_uris`）
- [X] T044 [US2] `auth/src/main/java/tech/toolkit/auth/oauth/ReauthenticationFilter.java`：处理 `prompt=login` 与 `max_age`
- [X] T045 [US2] `auth/src/main/java/tech/toolkit/auth/logout/LogoutController.java` 与 `LogoutService.java`：结束会话、删除授权、登记待通知的 client、重定向；退出请求的校验用框架自带的 provider 与默认校验器，只在响应处理里追加“令牌 sid 必须属于当前登录”（`SessionBoundLogoutValidator`，见 research.md D16）
- [X] T046 [US2] `auth/src/main/java/tech/toolkit/auth/logout/LogoutTokenFactory.java`、`LogoutDeliveryRepository.java`、`BackChannelDeliveryWorker.java`：指数退避，`FOR UPDATE SKIP LOCKED` 保证多实例不重复，每次投递重新签发 `logout_token`；并让 `DevClientSeeder` 登记的示例 client 带 `backchannel_logout_uri`，指向仅 dev 与测试 profile 的 `auth/src/main/java/tech/toolkit/auth/client/DevBackChannelReceiver.java`（只接收并在日志记录 `sid`，不记录令牌原文）
- [X] T047 [US2] 在 `auth/src/main/java/tech/toolkit/auth/logout/LogoutService.java` 中写 `LOGOUT`、`SESSION_REVOKED` 审计

**Checkpoint**: US1 + US2 都可独立验证。

---

## Phase 5: User Story 3 - 令牌刷新与吊销 (Priority: P2)

**Goal**: 刷新轮换、重用检测、会话绑定与吊销（FR-011、FR-012、FR-013）

**Independent Test**: 刷新成功；禁用账号后刷新被拒；旧刷新令牌重用使整条链失效（quickstart 场景 6–7）

### Tests for User Story 3（先写，并确认失败）

- [X] T048 [P] [US3] `auth/src/test/java/tech/toolkit/auth/oauth/RefreshRotationIT.java`：刷新得到新令牌；SSO 会话已结束或过期时返回 `invalid_grant`；按 client 配置的访问与刷新令牌有效期生效；访问令牌有效期 ≥ 刷新令牌有效期的配置被拒绝（FR-013）
- [X] T049 [P] [US3] `auth/src/test/java/tech/toolkit/auth/oauth/RefreshReuseDetectionIT.java`：已使用的刷新令牌再次出现返回 `invalid_grant`、整条授权链被删除、写 `REFRESH_REUSE_DETECTED`；并发刷新同一令牌只有一个成功
- [X] T050 [P] [US3] `auth/src/test/java/tech/toolkit/auth/oauth/RevocationIT.java`：用户被禁用、改密、client 被停用后刷新被拒绝，且不影响其他 client；`/oauth2/revoke` 需要 client 认证

### Implementation for User Story 3

- [X] T051 [US3] `auth/src/main/java/tech/toolkit/auth/oauth/ReuseDetectingAuthorizationService.java`：`OAuth2AuthorizationService` 装饰器，已使用标记 + 行级乐观锁
- [X] T052 [P] [US3] `auth/src/main/java/tech/toolkit/auth/oauth/SessionBoundRefreshValidator.java`：刷新时校验 `sid` 对应的 SSO 会话仍有效、用户启用且未因改密失效、client 启用
- [X] T053 [P] [US3] `auth/src/main/java/tech/toolkit/auth/oauth/ClientTokenSettingsCustomizer.java`：按 client 读取 `access_token_ttl_seconds` / `refresh_token_ttl_seconds`，空则用全局默认；校验访问令牌有效期必须短于刷新令牌（启动时校验全局默认，运行时对 client 配置兜底；比较另一项时取其实际生效值）
- [X] T054 [US3] 写 `TOKEN_REFRESHED`、`TOKEN_REVOKED`、`REFRESH_REUSE_DETECTED` 审计：`auth/src/main/java/tech/toolkit/auth/audit/AuthAuditListener.java`

---

## Phase 6: User Story 4 - 账号凭据与安全策略 (Priority: P2)

**Goal**: 密码策略、锁定、改密、登录限流、管理员对用户的管理、首个管理员（FR-015–FR-020、FR-019a）

**Independent Test**: 连续输错 5 次被锁定 15 分钟；管理员能创建、禁用用户，普通用户调用管理 API 被拒（quickstart 场景 8、11）

### Tests for User Story 4（先写，并确认失败）

- [x] T055 [P] [US4] `auth/src/test/java/tech/toolkit/auth/account/PasswordPolicyTest.java`：长度 ≥ 12、不在常见弱密码表中、不等于用户名
- [x] T056 [P] [US4] `auth/src/test/java/tech/toolkit/auth/account/LockoutIT.java`：连续失败 5 次锁定 15 分钟、锁定期内的失败不延长、登录成功后计数清零、阈值与时长可配置、多实例下计数原子；锁定期间的响应与密码错误逐字相同（不泄漏账号是否存在或是否被锁）
- [x] T057 [P] [US4] `auth/src/test/java/tech/toolkit/auth/account/ChangePasswordIT.java`：改密需要当前密码、满足策略、成功后其他会话与授权失效；`must_change_password` 为 true 时登录后先跳转改密页
- [x] T058 [P] [US4] `auth/src/test/java/tech/toolkit/auth/account/LoginRateLimitIT.java`：按来源 IP 的令牌桶，超限返回 `429` 且带 `Retry-After`
- [x] T059 [P] [US4] `auth/src/test/java/tech/toolkit/auth/admin/AdminUserApiIT.java`：对照 `contracts/admin-api.yaml` 测创建（`201`、重复用户名 `409`）、列表、详情、禁用、启用、解锁、重置密码、强制下线；未带令牌 `401`，普通用户与其他 client 的令牌 `403`；`auth.admin` 只发给“管理员 + 被允许的 client”，且每次请求实时校验该用户仍是启用的管理员
- [x] T060 [P] [US4] `auth/src/test/java/tech/toolkit/auth/bootstrap/BootstrapAdminIT.java`：`bootstrap` profile 创建第一个管理员，密码来自交互或标准输入而非命令行参数，`must_change_password` 为 true；同时登记公开 client `auth-admin`（授权码 + PKCE、scope 含 `auth.admin`、回环回调取自 `AUTH_ADMIN_REDIRECT_URIS`）；重复执行幂等，不重复登记

### Implementation for User Story 4

- [x] T061 [P] [US4] `auth/src/main/java/tech/toolkit/auth/account/PasswordPolicy.java` 与 `auth/src/main/resources/common-passwords.txt`
- [x] T062 [US4] `auth/src/main/java/tech/toolkit/auth/account/LockoutService.java`：单条 `UPDATE … RETURNING` 原子递增，`locked_until` 取当前时间加 15 分钟
- [x] T063 [US4] `auth/src/main/java/tech/toolkit/auth/account/LoginAttemptHandler.java`：登录成功与失败的处理，写 `ACCOUNT_LOCKED`
- [x] T064 [P] [US4] `auth/src/main/java/tech/toolkit/auth/account/ChangePasswordController.java` 与 `auth/src/main/resources/templates/change-password.html`
- [x] T065 [P] [US4] `auth/src/main/java/tech/toolkit/auth/account/LoginRateLimitFilter.java`：进程内令牌桶
- [x] T066 [US4] `auth/src/main/java/tech/toolkit/auth/admin/AdminSecurityConfig.java` 与 `AdminScopeCustomizer.java`：资源服务器、`auth.admin` scope 校验与实时管理员校验；只有 client 登记的 scope 含 `auth.admin` 且用户是启用的管理员时才签发该 scope（`auth-admin` client 由 bootstrap 登记，不依赖 dev 种子）
- [x] T067 [US4] `auth/src/main/java/tech/toolkit/auth/admin/AdminUserController.java` 与 `AdminUserService.java`：按 `contracts/admin-api.yaml`，禁用、重置密码、强制下线时调用 `AuthorizationRevocationService` 与 `LogoutService`
- [x] T068 [US4] `auth/src/main/java/tech/toolkit/auth/bootstrap/BootstrapAdminRunner.java`：创建第一个管理员，并幂等登记 `auth-admin` client
- [x] T069 [US4] 写 `USER_CREATED`、`USER_UPDATED`、`PASSWORD_CHANGED` 审计（含执行操作的管理员）：`auth/src/main/java/tech/toolkit/auth/audit/AuthAuditListener.java`

---

## Phase 7: User Story 5 - Client（应用）登记与管理 (Priority: P3)

**Goal**: 在线登记、停用、轮换 client，即时生效（FR-021–FR-024、FR-023）

**Independent Test**: 登记 client4 并走完登录；停用 client2 后只有它被拒绝（quickstart 场景 9）

### Tests for User Story 5（先写，并确认失败）

- [x] T070 [P] [US5] `auth/src/test/java/tech/toolkit/auth/admin/AdminClientApiIT.java`：对照 `contracts/admin-api.yaml` 测创建（机密 client 的 secret 只返回一次且库中只存哈希）、列表与详情不含 secret、`PATCH` 即时生效无需重启、停用与启用、凭据轮换后旧凭据被拒、重复 `clientId` 返回 `409`、访问令牌有效期不短于刷新令牌的创建与更新返回 `400`
- [x] T071 [P] [US5] `auth/src/test/java/tech/toolkit/auth/client/ClientIsolationIT.java`：一个 client 的凭据、回调地址、授权码与令牌不能被另一个 client 使用；新登记的 client4 可完成登录；停用 client2 不影响 client1、client3

### Implementation for User Story 5

- [x] T072 [US5] `auth/src/main/java/tech/toolkit/auth/client/ClientService.java`：`RegisteredClient` 与 `auth_client_ext` 的读写、secret 哈希
- [x] T073 [US5] `auth/src/main/java/tech/toolkit/auth/admin/AdminClientController.java`：按 `contracts/admin-api.yaml`
- [x] T074 [US5] `auth/src/main/java/tech/toolkit/auth/oauth/ClientTokenSettingsCustomizer.java`：停用的 client 对授权、令牌与退出端点一律不可见（不另设 validator）；停用时由 `AdminClientService` 经 `client.ClientAuthorizations` 端口调用 `AuthorizationRevocationService` 删除其授权
- [x] T075 [US5] 写 `CLIENT_CREATED`、`CLIENT_UPDATED` 审计：在 `auth/src/main/java/tech/toolkit/auth/admin/AdminClientService.java` 中随每次变更记录（与用户管理一致，带管理员 actor，不含 secret）

---

## Phase 8: User Story 6 - 下游服务验证令牌 (Priority: P3)

**Goal**: 元数据、JWKS 与密钥轮换（FR-025–FR-027）

**Independent Test**: 用 JWKS 离线验证令牌；轮换密钥后旧令牌仍可验证（quickstart 场景 10）

### Tests for User Story 6（先写，并确认失败）

- [x] T076 [P] [US6] `auth/src/test/java/tech/toolkit/auth/oauth/DiscoveryAndJwksIT.java`：`/.well-known/openid-configuration`、`/.well-known/oauth-authorization-server` 与 `/oauth2/jwks` 可访问并返回合法内容（公钥内容的断言见 T016）；离线验证令牌的签名、`typ`、`iss`、`exp`
- [x] T077 [P] [US6] `auth/src/test/java/tech/toolkit/auth/oauth/KeyRotationIT.java`：轮换过程中旧令牌在有效期内仍可验证、新令牌用新密钥签发、失败率为 0（SC-007）；未知 `kid` 时可取到最新公钥
- [x] T078 [P] [US6] `auth/src/test/java/tech/toolkit/auth/oauth/TokenVerificationTest.java`：被篡改、过期、`iss` 不符的令牌验证失败

### Implementation for User Story 6

- [x] T079 [P] [US6] `auth/src/test/java/tech/toolkit/auth/support/TokenVerifier.java`：测试用的下游验证工具，同时更新 `auth/AGENTS.md` 的“密钥轮换”步骤（先发布新公钥 → 切换签名 → 旧公钥保留至少一个访问令牌有效期后移除）

---

## Phase 9: Polish & Cross-Cutting Concerns

- [x] T080 [P] `auth/src/main/resources/application.yml` 仅暴露 `/actuator/health`（不含敏感信息），测试：`auth/src/test/java/tech/toolkit/auth/config/HealthEndpointIT.java`（FR-029）
- [x] T081 [P] `auth/src/test/java/tech/toolkit/auth/perf/LoginLoadIT.java`：500 个并发登录，95% 在 2 秒内完成（SC-004），单独标记为性能测试
- [x] T082 [P] `auth/src/test/java/tech/toolkit/auth/audit/LogHygieneIT.java`：全流程后检查日志与审计中没有密码、令牌原文（SC-005、FR-028）
- [x] T083 [P] `auth/src/test/java/tech/toolkit/auth/cleanup/ExpiredDataCleanupIT.java`：`oauth2_authorization` 中授权码、访问令牌、刷新令牌过期时间都早于“现在减 1 天”的记录被删；`auth_sso_client` 中 `session_id` 已不在 `spring_session` 的行被删；`auth_logout_delivery` 中 `DELIVERED` 或 `GAVE_UP` 且超过 7 天的行被删；`auth_used_refresh_token` 中超过刷新令牌最长有效期（8 小时）的行被删；未过期或仍在使用的数据保留；多实例同时触发只有一个实例执行
- [x] T084 `auth/src/main/java/tech/toolkit/auth/cleanup/ExpiredDataCleanupJob.java`：`@Scheduled`，用 PostgreSQL advisory lock 保证同一时刻只有一个实例清理，保留天数取配置 `auth.cleanup.*`；不清理 `auth_audit_event`
- [ ] T085 运行 `mvn spotless:apply` 与 `mvn verify`（在 `auth/` 下），确认行覆盖率 ≥ 90%，排除路径只有 `**/AuthApplication.class`
- [ ] T086 按 `specs/001-uam-oauth2-sas/quickstart.md` 逐条跑 11 个场景，命令与实际不符之处回改 quickstart 与 `auth/AGENTS.md`；同时完成 quickstart 的“人工验收”（SC-001、SC-006）并记录结果
- [ ] T087 功能完成后归档：把已落地的需求与设计沉淀到 `docs/achieve-specs/auth.md` 与 `docs/system-design/`（根 AGENTS.md「Project Context」）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（Phase 1）**：无依赖，先做；T001 的结论决定 T002 的依赖坐标。
- **Foundational（Phase 2）**：依赖 Setup，**阻塞所有 user story**。
- **User Story 阶段**：都依赖 Foundational。
  - **US1** 无其他 story 依赖，是 MVP。
  - **US2** 依赖 US1（要先有登录与授权）；T043 的 `ClientExtRepository` 也被 US5 复用。
  - **US3** 依赖 US1；吊销服务（T019）已在 Foundational 完成，被 US2 的退出、US3 的吊销、US4 的管理操作、US5 的停用共用。
  - **US4** 依赖 US1；T067 依赖 T019（Foundational）与 T045（US2）。
  - **US5** 依赖 US1、US4（T066 的管理员鉴权）；吊销服务已在 Foundational。
  - **US6** 依赖 US1；JWK 源（T017）已在 Foundational 完成。
- **Polish**：依赖所有要交付的 story。

### 推荐顺序

Phase 1 → Phase 2 → US1 → US2 → US3 → US4 → US5 → US6 → Polish。

### Within Each User Story

- 测试先写并确认失败，再写实现（根 AGENTS.md「测试先行」）。
- 实体与仓库 → 服务 → 端点与页面 → 审计。
- 同一文件上的任务（如多处修改 `AuthAuditListener.java`）按顺序做，不并行。

### Parallel Opportunities

- Setup 中 T003、T004、T006、T007 可并行。
- Foundational 中 T012 与 T014 可并行；T016 与 T018 可并行。
- 每个 story 的测试任务（标 [P]）可并行写。
- US1 完成后，US6 可与其他 story 并行推进。

### Parallel Example: User Story 1

```text
并行写测试：T020、T021、T022、T023、T024、T025
并行做实现：T026、T027、T036
随后串行：T028 → T029 → T030 → T031 → T032 → T033 → T034 → T035
```

---

## Implementation Strategy

### MVP First（只做 User Story 1）

1. Phase 1 → Phase 2 → Phase 3（US1）。
2. **停下来验证**：用示例 client 走通 quickstart 场景 1–3。
3. 通过后再继续 US2。

### Incremental Delivery

- US1 之后：可演示完整的授权码登录。
- 加 US2：多应用 SSO 与统一退出。
- 加 US3、US4：令牌安全与账号安全达到可上线的底线（**上线前必须完成**）。
- 加 US5、US6：运维与下游接入的便利性。

### Notes

- 提交粒度：每个测试加对应实现一个提交，信息说明“为何”。
- 不要把 `auth-web`（Portal BFF）的任何内容放进本清单，它是后续特性。
- 遇到框架没有现成支持的部分（T046、T051），按 plan.md「设计与实现的参考基准」所列标准实现，并在代码注释里写明依据的条款。
