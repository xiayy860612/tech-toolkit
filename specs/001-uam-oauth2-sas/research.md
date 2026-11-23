# Research: auth

Phase 0 产出。每项给出 Decision / Rationale / Alternatives。无遗留 NEEDS CLARIFICATION；标注“实现时核实”的是版本或坐标级事实，不影响设计。

## D1 技术栈与框架版本

- **Decision**: Java 21、Spring Boot 4.1.x（与 `main` 上 backend 一致）、Maven。授权服务器用 Spring Authorization Server（SAS）；SAS 自 Spring Security 7.0 起并入 Spring Security，不再有独立的 2.x 发行线，坐标仍是 `spring-security-oauth2-authorization-server`，版本随 Spring Security。
- **Rationale**: 用户指定 SAS；Boot 4.1 / Security 7 是 `main` 的既有栈，同仓库内保持一致，避免两套 Security 大版本。
- **Alternatives**: SAS 1.5.x + Boot 3（1.5 是最后一个独立版本，且与 `main` 栈不一致）——拒绝。
- **已核实（T001，2026-11-22）**: Maven Central 上 Boot 最新为 4.1.3、Spring Security 为 7.1.3。`spring-boot-starter-oauth2-authorization-server` 已标注弃用，改用 `spring-boot-starter-security-oauth2-authorization-server`（artifact 与旧名的依赖相同）。`OAuth2AuthorizationServerConfigurer` 在 spring-security-config 里，用 `http.oauth2AuthorizationServer(...)` 配置。

## D2 项目结构

- **Decision**: 仓库根下新建 `auth/`，它就是 auth 这一个 Maven 项目（单模块）。第一个管理员的创建做成一次性运维命令（`bootstrap` profile 下的 `ApplicationRunner`），不单独建 CLI 模块。MVP 不含 auth-web。
- **Rationale**: 用户确认 MVP 只做 auth，`auth/` 目录就是它。只有一个可部署单元，不需要多模块；CLI 只有一个功能，YAGNI。
- **Alternatives**: 多模块（auth + auth-web）——auth-web 后移，等它出现再拆；共享库、独立 CLI——拒绝。

## D3 存储

- **Decision**: PostgreSQL 18 + Flyway。SAS 的 JDBC 实现保存 `oauth2_registered_client` 与 `oauth2_authorization`；SSO 会话用 Spring Session JDBC。不引入 Redis。
- **Rationale**: 多实例共享会话与授权的最小方案；与 `main` 的数据库选型一致；SC-004（500 并发登录）在 PostgreSQL 上足够。
- **Alternatives**: Redis 存会话——多一个运维依赖，当前规模不需要；留作后续优化。

## D4 令牌格式与有效期

- **Decision**: 访问令牌与 ID Token 为 JWT（ES256），刷新令牌为不透明串且每次轮换。默认：访问令牌 10 分钟；刷新令牌**绑定 SSO 会话**，刷新时校验对应 SSO 会话仍有效，因此刷新令牌的实际寿命不超过 SSO 绝对期（8 小时）。
- **Rationale**: US6 要求下游离线验证，所以用 JWT；FR-014a 要求任何应用的登录状态都不长于 auth 会话，刷新令牌必须受会话约束。
- **影响 spec**: spec 的 Assumptions 原写“刷新令牌默认数天”，与 FR-014a 冲突，已改为“不长于 SSO 绝对期”。
- **Alternatives**: 不透明访问令牌 + introspection——每次请求回调 auth，违背 US6。

## D5 签名密钥与轮换

- **Decision**: ES256；密钥集由环境变量或挂载文件提供，可含多把（1 把当前签名 + N 把仅用于验证）；JWKS 发布全部公钥。轮换 = 先发布新公钥 → 切换签名密钥 → 旧公钥保留至少一个访问令牌有效期（10 分钟）后移除。`dev` profile 才允许临时密钥。
- **Rationale**: 满足 FR-026/FR-027 与 SC-007，且私钥不入库不入镜像。配置变更靠滚动重启，不做热加载（YAGNI）。
- **Alternatives**: 密钥存数据库——私钥落库，扩大泄漏面，拒绝。

## D6 密码存储与策略

- **Decision**: argon2id（Spring Security `Argon2PasswordEncoder`，依赖 BouncyCastle）。策略默认：长度 ≥ 12，禁止内置的常见弱密码表中的值，不得等于用户名。用户不存在时也执行一次等耗时的哈希比对，防止通过响应时间枚举用户名。
- **Rationale**: FR-007、FR-016、FR-017。
- **Alternatives**: bcrypt——可用但 argon2id 是当前推荐，`main` 的设计文档也定为 argon2id。

## D7 账号锁定与限流

- **Decision**: 失败计数与 `locked_until` 存在用户表，用单条 `UPDATE … RETURNING` 原子递增，多实例一致；锁定 15 分钟、期间失败不延长（FR-018）。登录端点按来源 IP 限流用进程内令牌桶。
- **Rationale**: 账号级锁定必须跨实例一致；来源级限流是补充防线，进程内即可。
- **已知限制**: 多实例下限流按实例计算，总量放宽为实例数倍；需要精确限流时由网关承担（后续）。
- **Alternatives**: Redis 分布式限流——暂不引入。

## D8 刷新令牌重用检测

- **Decision**: 自定义 `OAuth2AuthorizationService` 装饰器：刷新令牌使用后标记为已使用；已使用的刷新令牌再次出现，删除同一 `oauth2_authorization` 记录（即整条授权链）并写审计。并发刷新靠数据库行级乐观锁，只有一个成功。
- **Rationale**: SAS 默认只做轮换，不做重用即吊销整条链（FR-011、边界情况“并发刷新”）。
- **Alternatives**: 仅靠轮换——重用时旧令牌被拒但新令牌仍有效，不满足 FR-011。

## D9 吊销与退出的传播

- **Decision**:
  1. 吊销：禁用、改密、管理员强制下线时，按 `principal_name` 删除该用户全部 `oauth2_authorization` 与 SSO 会话（FR-012）。
  2. 退出传播：SAS 不提供服务端的 OIDC back-channel logout 发送，自行实现。client 登记 `backchannel_logout_uri`；会话期间记录“会话 ↔ client”关系；会话结束时向每个 client 异步 POST 带 `sid` 的 `logout_token`（ES256 JWT），失败按指数退避重试。各 App BFF 用 Spring Security 的 OIDC back-channel logout 接收。
  3. 兜底：通知投递失败时，刷新令牌已被删除，BFF 最迟在访问令牌过期（10 分钟）后无法续期而退出。
- **Rationale**: 实现 FR-014/FR-014a/SC-009。
- **风险**: SC-009（10 秒内失效）依赖通知送达；送不到时退化为最多 10 分钟。已在 plan 的风险里登记，是否要把访问令牌压到更短由你决定。
- **Alternatives**: 每个 BFF 每次请求向 auth 校验会话——延迟与可用性耦合更紧，拒绝。

## D10 Portal 介导的登录流程（后续特性，MVP 不做）

MVP 各 App BFF 直接向 auth 发起授权请求。以下是 auth-web 加入时的设计，保留备查；auth 无需为此做任何改动（auth-web 只是普通 client）。

- **Decision**: App BFF 发现未登录 → 302 到 auth-web `/login?return_to=<App 地址>` → auth-web（它自己是 client）走授权码流程登录 auth → 回到 auth-web 后 302 回 `return_to` → App BFF 随后用自己的 client 发起授权码流程，auth 已有 SSO 会话，免输入凭据直接签码。`return_to` 只允许配置里的白名单来源，防开放重定向。
- **Rationale**: 对应草图中各 BFF 到 Portal 的 redirect 连线，且与 FR-030（登录页由 auth 提供）一致，凭据只经过 auth。
- **Alternatives**: App BFF 直接跳 auth——更短，但草图里的 Portal 入口就没有作用，拒绝；该取舍请在评审 plan 时确认。

## D11 管理 API 的认证

- **Decision**: 管理 API 与授权服务器同进程，作为资源服务器验证访问令牌，要求 scope `auth.admin`。该 scope 只在“用户是管理员 + client 被允许申请它”时签发；每次请求再查库确认用户仍是启用的管理员。管理 API 只供运维工具使用，通过一个公开 client（`auth-admin`，授权码 + PKCE，回环回调）登录获取令牌；该 client 由首个管理员的 bootstrap 命令一并登记（回调地址取自 `AUTH_ADMIN_REDIRECT_URIS`），不依赖 dev 种子，生产环境同样存在。
- **Rationale**: 对应 Clarification 2（管理员登录后调用）。双重校验让“禁用管理员”立即生效，不必等令牌过期。
- **Alternatives**: 独立服务密钥——已被否决。

## D12 用户标识

- **Decision**: 用户主键为自增 `bigint`，令牌 `sub` 为其十进制字符串；用户名统一小写存储并做唯一约束。
- **Rationale**: 沿用团队此前对自增整型 ID 的偏好；单一用户池，无需全局唯一的随机 ID。
- **Alternatives**: UUID——可避免 `sub` 被枚举，但本期无此诉求。

## D13 审计

- **Decision**: `auth_audit_event` 表只允许应用角色 INSERT，同时输出结构化日志；事件字段见 data-model.md；密码、令牌原文不入库不入日志，含密钥字段的类自带脱敏 `toString()`（ArchUnit 约束，沿用 backend 做法）。
- **Rationale**: FR-028、SC-005。

## D14 测试与质量门禁

- **Decision**: JUnit + Testcontainers（PostgreSQL）做集成与契约测试；ArchUnit 约束分层与禁用项；JaCoCo 行覆盖率每个模块 ≥ 90%；Spotless（google-java-format）。流程测试用 Spring 的 MockMvc / `TestRestTemplate` 走完整授权码流程，不依赖外部 IdP。
- **Rationale**: 根 AGENTS.md 的“测试先行”与覆盖率门禁。
- **Alternatives**: 浏览器 E2E（Playwright）——登录页只有一个表单，先不引入。

## D15 auth-web 形态（后续特性，MVP 不做）

- **Decision**: Spring Boot（servlet）+ `oauth2Login`，Thymeleaf 极简页面（入口页、已登录页、错误页）；会话 cookie 为 HttpOnly + Secure + SameSite=Lax，令牌只在服务端；提供 `/login`、`/logout`、back-channel logout 接收端点。
- **Rationale**: spec 范围只要求“入口与登录跳转”；不引入前端工程。
- **Alternatives**: SPA 前端——门户业务功能不在本期范围，拒绝。

## D16 退出校验与 `sid`：用框架原生的做法

- **Decision**: `sid` = HTTP 会话 id 的 SHA-256 哈希（base64url，无填充），与 Spring Authorization Server 写进 ID token 的 `sid` 完全同一算法。退出端点用框架自带的 `OidcLogoutAuthenticationProvider` 与默认的 `OidcLogoutAuthenticationValidator`，只追加一条校验：当前会话 id 的哈希必须等于令牌里的 `sid`。我们不再自己生成 `sid`，也没有自定义的退出校验 provider。
- **Rationale（对照实验，2026-11-22）**: 把框架 provider 挂回去，用我们自己的 UUID `sid` 时被拒（`invalid_token ... sid`）；改用框架的 `sid` 后，退出的拒绝用例（未登记的退出后地址、乱写的令牌、别的登录的令牌）都由框架直接处理。登录时会话 id 虽会轮换，但授权发生在轮换之后，哈希在整个登录期间不变，实测等于 `spring_session.session_id` 的哈希。
- **追加的校验**: 框架的 `sid` 比对依赖进程内的 `SessionRegistry`，多实例下退出请求落到别的实例时，我推测比对会因查不到会话而被跳过（未实测）。所以用 `setAuthenticationValidator` 追加一条不依赖注册表的校验：令牌 `sid` 必须等于当前会话 id 的哈希。
- **令牌里的 `sid`**: 访问令牌与 ID token 都由 `TokenCustomizer` 按授权记录里存的 `sid` 写入。令牌发放发生在 token 端点，可能与授权请求不在同一个实例，那时框架的注册表里没有该会话，不会自己写入 `sid`。
- **替代了**: 之前的自生成 UUID `sid`（`SsoSessionIds.assign`）与自写的 `LogoutRequestAuthenticator`；它们基于“会话 id 轮换使框架的 `sid` 不稳定”这一判断，实验证明该判断不成立。
- **Alternatives**: 保留自定义校验——多维护一段与安全相关的代码，且重复了框架已有的检查。

## D17 刷新令牌：重用检测、会话绑定与按 client 的有效期（US3 实现）

- **重用检测（D8 的实际做法）**: 框架轮换刷新令牌，但授权记录里只保留最新一个，用过的旧令牌直接消失，无法与乱写的令牌区分。所以每次轮换把被替换的令牌记成哈希（`auth_used_refresh_token`）；旧令牌再出现时，在表里查到它，就删除整条授权（连替换它的新令牌一起失效）并写 `REFRESH_REUSE_DETECTED`。并发用同一个令牌时，写这张表的主键冲突让输家得到 `invalid_grant`。
- **会话绑定**: 刷新时校验用户启用、改密时间不晚于令牌签发时间、client 未停用，并且 `sid` 对应的 SSO 登录仍在有效期内（空闲看最后访问时间，绝对看创建时间）。登录用 `sid` 在 `spring_session` 里查：按用户的 `principal_name`（有索引）缩小范围，再在 SQL 里用与 Java 同一算法算哈希比对。通过校验的刷新同时把登录的最后访问时间更新为现在（FR-003：刷新算活动）。
- **按 client 的有效期**: `ClientTokenSettingsCustomizer` 包装 `RegisteredClientRepository`，加载 client 时读 `auth_client_ext` 的覆盖值；覆盖后访问令牌不短于刷新令牌时忽略覆盖并记警告。全局默认在 `AuthProperties.Tokens` 构造时校验，违反则启动失败（FR-013）。
- **撤销审计**: 撤销端点与授权码重用都会删除整条授权，用请求的 `grant_type` 区分原因，写 `TOKEN_REVOKED`；刷新写 `TOKEN_REFRESHED`，不再混为 `TOKEN_ISSUED`。
- **未登录的 API 请求**: 只有真正要 HTML 的请求才跳登录页，其余返回 401（`Accept: */*` 或缺省都按 API 请求处理）。
- **已知取舍**: 会话查找是对该用户的会话做哈希比对，用户的会话数很多时代价线性增长；如果出现这种情况，可以给会话行加一列保存 `sid`。

## D18 账号安全与管理员用户管理（US4 实现）

- **密码策略**: `PasswordPolicy` 返回违反项（`TOO_SHORT`、`TOO_COMMON`、`SAME_AS_USERNAME`）；常见口令表是 classpath 里的 `common-passwords.txt`。创建、改密、管理员重置都走同一个 `AccountSecurityService`。
- **锁定**: 失败计数与 `locked_until` 用一条原子 SQL 更新（带 `FOR UPDATE` 的 CTE），判断用数据库的 `now()`，不用应用时钟，多实例一致；锁定期内的失败不延长。被锁的账号**先校验密码再拒绝**，应答与密码错误逐字相同（FR-018）。改密时间用应用时钟写入，与签发时间比较时同一时钟。
- **限流**: 按来源 IP 的进程内令牌桶，超限返回 429 加 `Retry-After`；测试 profile 把容量调到很大，`LoginRateLimitIT` 再调小。
- **强制改密**: `must_change_password` 为真时，`PasswordChangeRequiredFilter` 在所有授权请求之前把用户送到 `/account/password`（会话属性 `auth.mustChangePassword`）。管理员重置和 bootstrap 创建的账号都带这个标志。
- **端口避免包循环**: 改密、禁用、强制下线要结束用户的登录并删除授权，而这些实现在 `logout` 与 `oauth` 里。`account` 定义接口 `UserSessions`，`logout.UserSessionsService` 实现它；同理 `client.ClientAuthorizations` 由 `oauth.AuthorizationRevocationService` 实现。ArchUnit 守着 package 无环。
- **管理员 scope**: `auth.admin` 只在签发访问令牌时保留给"此刻启用的管理员"（`AdminScopeCustomizer`），任何人都可以申请它，但拿不到。管理 API 是无状态的资源服务器链（`@Order(0)`，`/admin/**`），要求 scope 且每次请求再查库确认管理员仍启用，所以禁用管理员立即生效。未带令牌 401，不合格 403。
- **bootstrap**: `bootstrap` profile 的 `ApplicationRunner`，密码只从控制台或标准输入读取，任何含 "password" 的命令行参数一律拒绝。它同时登记公共 client `auth-admin`（PKCE，回调取 `AUTH_ADMIN_REDIRECT_URIS`）。幂等：已存在的不改。完整应用上下文是必需的，所以该 profile 仍启动 web 服务器，但监听随机端口、关闭后台任务，执行完调用 `SpringApplication.exit` 结束进程（T086 实跑发现 `web-application-type: none` 会因缺少 `HttpSecurity` 而起不来）。它与服务一样需要 `AUTH_SIGNING_KEYS`。
- **审计**: `USER_CREATED`、`USER_UPDATED`、`PASSWORD_CHANGED`，带执行管理员的 `actor`；审计细节的键名含 `password/secret/token/authorization` 会被拒绝。

## D19 Client 在线管理（US5 实现）

- **读写走原始仓库**: `ClientService` 自己用 `JdbcRegisteredClientRepository` 读写 client 表，不经过 OAuth 端点用的那个仓库，因为后者对停用的 client 返回 `null`，管理员必须还能看到并重新启用它。
- **停用 = 对 OAuth 端点不可见**: `ClientTokenSettingsCustomizer` 在加载 client 时发现 `auth_client_ext.enabled = false` 就返回 `null`，于是授权、令牌、退出端点都把它当作未知 client（授权端点 400 错误页，令牌端点 401 `invalid_client`）。没有单独的 `ClientEnabledValidator`。停用时同事务调用 `ClientAuthorizations.revokeByClientId` 删除其全部授权；背向通道通知的查询本来就带 `enabled` 条件。
- **校验**: `clientId` 为 1–100 位 `[A-Za-z0-9._-]` 且唯一；回调地址与退出后地址须为不带 fragment 的绝对 http(s) 地址，且只有回环地址可以用 http（RFC 9700）；至少一个 scope；有效期不得小于 60 秒；访问令牌有效期（含未设置时取全局默认或已登记值的**实际生效值**）必须短于刷新令牌。
- **secret**: 32 字节随机数，base64url；只在创建与轮换时返回，库中是 `DelegatingPasswordEncoder`（argon2id）哈希；轮换立即替换，旧 secret 即刻失效；公共 client 轮换返回 400。
- **PATCH**: 请求里没写的字段不变；`backchannelLogoutUri` 传空串表示清除。
- **退出后地址只存一处**: 以 `oauth2_registered_client` 里的为准（框架退出校验读的就是它）；`auth_client_ext.post_logout_redirect_uris` 列保留但不再使用。
- **已知取舍**: 若 `auth-admin` 被停用，再次运行 bootstrap 会因查不到它而重复登记并失败；不额外处理。

## D20 发现元数据、JWKS 与密钥轮换（US6 实现，含两处缺陷修复）

- **轮换时无法签名（缺陷，已修）**: 把"当前密钥 + 仅验证的旧密钥"都放进 `JWKSource` 后，`NimbusJwtEncoder` 找到多把 ES256 密钥会拒绝签名，按 D5 的步骤轮换会让登录整体失败。现在 `JwkSourceConfig.jwtEncoder` 只用当前密钥签名，JWKS 端点仍发布全部公钥。T086 用真实重启走过"发布 → 切换 → 移除"三步验证。
- **发现文档通告不实（缺陷，已修）**: 框架默认通告 `RS256`、`client_credentials`、`token-exchange`。现在 OIDC 与 OAuth 两份元数据都只通告 `ES256` 以及 `authorization_code`、`refresh_token`。
- **下游验证**: 缓存 JWKS，遇到未知 `kid` 重新拉取一次再判定；校验 `alg`（仅 ES256）、`typ`（`at+jwt`）、`iss`、`exp`。测试里的 `support/TokenVerifier` 是参考实现。
- **旧令牌的下限**: 旧公钥移除后，用它签的令牌无法再验证，所以旧公钥至少保留一个访问令牌有效期。

## D21 过期数据清理、健康检查与性能（Polish）

- **清理任务**: `ExpiredDataCleanupJob`，`@Scheduled`（默认启动后 1 分钟、之后每小时），所有实例都跑调度，但 `pg_try_advisory_xact_lock` 让同一时刻只有一个实例执行；时间一律用数据库时钟。授权按 `greatest(code, access, refresh 的过期时间)` 早于保留期删除，没有任何过期时间的行保留；`auth_sso_client` 按 `sid` 的哈希对 `spring_session` 做 `not exists`；已完成的退出通知用 `next_attempt_at`（对已完成的通知就是最后一次尝试的时间）计龄。不清理审计表。
- **健康检查**: 只暴露 `/actuator/health`，匿名可访问，只含状态。其余端点不暴露。
- **性能（SC-004）**: 500 并发登录，实测 p95 约 1.7 秒（12 线程笔记本，上限 2 秒）。瓶颈是 argon2：单次校验约 15–18 毫秒，12 线程吞吐约 300–400 次/秒。**测量必须关闭 JaCoCo**：agent 默认也给 BouncyCastle 的循环插桩，同一个测试 p95 会涨到 10 秒以上。该测试标记 `performance`，默认不跑。若部署硬件更弱，应增加实例，而不是降低 argon2 参数（当前内存参数 16 MiB 已低于 OWASP 建议的 19 MiB 下限，不应再降）。
- **日志卫生**: `LogHygieneIT` 跑完全流程后断言日志与审计里没有密码、哈希、令牌、授权码、client secret。
- **直接登录后落在 `/`**: auth 没有主页，没有待处理授权请求的登录成功后会重定向到 `/`，得到 404。这是"auth 只有登录页、改密页、错误页、已退出页"的设计结果，登录必须由 client 的授权请求发起；不为此加主页。
