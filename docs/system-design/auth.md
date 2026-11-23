# auth：架构与实现（已落地）

代码在 `auth/`，命令与环境变量见 [`auth/AGENTS.md`](../../auth/AGENTS.md)，需求与领域见 [`docs/achieve-specs/auth.md`](../achieve-specs/auth.md)，每个设计取舍的依据见 `specs/001-uam-oauth2-sas/research.md`（D1–D21）。

## 技术栈

Java 21、Spring Boot 4.1.3、Spring Security 7.1.3（Spring Authorization Server 已并入，用 `http.oauth2AuthorizationServer(...)` 配置）、PostgreSQL 18 + Flyway、Spring Session JDBC、Thymeleaf（登录页、改密页、错误页、已退出页）、BouncyCastle（argon2id）、Maven 单模块。测试：JUnit 6、Testcontainers、MockMvc、ArchUnit、JaCoCo、Spotless。无状态，可水平扩展，会话与授权都在 PostgreSQL。

## 代码结构

`tech.toolkit.auth` 下按职责分 package，**package 之间不得有环**（ArchUnit 守着）：

| package | 职责 |
|---|---|
| `account` | 用户、密码策略、锁定、限流、改密、强制改密过滤器；定义端口 `UserSessions` |
| `admin` | 管理 API（用户、client）、`auth.admin` scope 的签发限制、管理员资源服务器链 |
| `audit` | 审计事件与服务（`AuditService` 拒绝含密钥字样的细节键） |
| `bootstrap` | 首个管理员与 `auth-admin` 的一次性命令 |
| `client` | client 登记与扩展（`ClientService`、`ClientExtRepository`）、dev 种子；定义端口 `ClientAuthorizations` |
| `cleanup` | 过期数据清理任务 |
| `config` | `AuthProperties`（`auth.*` 配置记录） |
| `logout` | 退出、back-channel 通知投递、`UserSessionsService` |
| `oauth` | 授权服务器配置、令牌定制、重用检测与会话绑定装饰器、JWK 与签名密钥、吊销 |
| `session` | SSO 绝对期过滤器、会话活动、`sid` 计算 |

依赖方向靠两个端口避免循环：`account.UserSessions` ← `logout.UserSessionsService`；`client.ClientAuthorizations` ← `oauth.AuthorizationRevocationService`。

## 请求链路（三条 SecurityFilterChain）

| 顺序 | 匹配 | 说明 |
|---|---|---|
| 0 | `/admin/**` | 无状态资源服务器；`SCOPE_auth.admin` 且 `AdminScopeCustomizer.isEnabledAdmin` 每次请求查库；关闭 CSRF |
| 1 | 授权服务器端点（`/oauth2/*`、`/userinfo`、`/connect/logout`、发现、JWKS） | 先 `ReauthenticationFilter`（读出登录并校验），再 `PasswordChangeRequiredFilter`；只有真正要 HTML 的请求才跳登录页，其余 401 |
| 2 | 其余（`/login`、`/account/password`、`/logged-out`、`/error`、`/actuator/health`） | 表单登录，`LoginRateLimitFilter` 与 `LoginRequestExpiryFilter` 在前；登录成功处理器把须改密的用户送去改密页 |

## OAuth 核心的装配

- `OAuth2AuthorizationService` 是装饰器链：`AuthorizationCodeReuseHandler` → `ReuseDetectingAuthorizationService` → `SessionBoundRefreshValidator` → `SessionLinkingAuthorizationService` → `JdbcOAuth2AuthorizationService`。
  - 授权码重用：删除它签出的授权并审计。
  - 刷新重用：被替换的刷新令牌哈希进 `auth_used_refresh_token`，再出现即整条授权失效；主键冲突裁决并发。
  - 会话绑定：刷新时校验用户启用、改密时间、client 启用、`sid` 对应的登录仍有效，通过则更新会话活动。
  - 授权落库时写 `auth_session_id`，退出与吊销按它精确删除。
- `RegisteredClientRepository` 被 `ClientTokenSettingsCustomizer` 包装：按 client 覆盖令牌有效期；`enabled = false` 的 client 返回 `null`，所有 OAuth 端点都把它当作未知。管理侧的 `ClientService` 绕过这层，直接读写 client 表。
- 令牌：`TokenCustomizer` 写 RFC 9068 的 claims（`at+jwt`、`client_id`、`sid`、scope 为空格分隔字符串），并调用 `AdminScopeCustomizer` 去掉不该拿到的 `auth.admin`。`JwtEncoder` 只用当前密钥（`JwkSourceConfig.jwtEncoder`），JWKS 发布全部公钥；`JwtDecoder` 只接受 ES256 与 `typ=at+jwt`。
- 授权请求校验：回调精确匹配、`state` 必填、仅 S256。发现文档只通告 ES256、authorization_code、refresh_token。
- 退出：框架自带的 `OidcLogoutAuthenticationProvider` 加一条 `SessionBoundLogoutValidator`（令牌 `sid` 必须等于当前会话 id 的哈希）。

## 存储

表结构与清理规则见 `specs/001-uam-oauth2-sas/data-model.md`。要点：自有表前缀 `auth_`，`oauth2_*` 与 `spring_session*` 沿用框架结构（`oauth2_authorization` 多一列 `auth_session_id`）。Flyway 用 owner 角色迁移，应用以受限的 `auth_app` 角色运行：审计表只有 INSERT/SELECT，`auth_used_refresh_token` 有 INSERT/SELECT/DELETE。所有按时间的判断（锁定、清理、会话）用数据库时钟。

## 密码与安全细节

- argon2id 经 `DelegatingPasswordEncoder`（id `argon2@SpringSecurity_v5_8`）；密码、哈希、令牌不进日志与审计，含密钥字段的类必须自带脱敏 `toString()`（ArchUnit 规则）。
- 锁定用一条原子 SQL（`FOR UPDATE` 的 CTE）更新失败计数与 `locked_until`；被锁账号先校验密码再拒绝，应答不可与密码错误区分。
- 登录限流：每个来源 IP 一个令牌桶（进程内）。
- 管理 API 的请求体里含密码或 secret 的记录类，`toString()` 已脱敏；`ClientCreated` 只有创建时携带 `clientSecret`。

## 后台任务

| 任务 | 调度 | 多实例 |
|---|---|---|
| `BackChannelDeliveryWorker` | 固定延迟（默认 1 秒），`auth.logout.worker-enabled` | 行级 `SKIP LOCKED`，不会重复发送 |
| `ExpiredDataCleanupJob` | 启动 1 分钟后，之后每小时，`auth.cleanup.enabled` | 事务级 advisory lock，同一时刻只有一个实例清理 |
| Spring Session 清理 | 每 10 分钟 | 框架自带 |

## 配置与部署

- 环境变量见 `auth/.env.example`：数据库（owner 与 app 两个角色）、`AUTH_ISSUER`（固定，不从请求推导）、`AUTH_SIGNING_KEYS`（PKCS#8 PEM 文件，逗号分隔，第一把签名）、`AUTH_ADMIN_REDIRECT_URIS`、`AUTH_PORT`（默认 9000）。只有 `dev` 与 `test` profile 允许临时签名密钥；dev 的示例用户与 client secret 来自 `AUTH_DEV_*`，没有默认值。
- 首次部署运行 `bootstrap` profile（需与服务相同的环境变量）；它启动完整上下文、监听随机端口、关闭后台任务、完成后退出。
- 密钥轮换：先发布新公钥（放在当前之后）→ 滚动重启 → 调到第一位 → 再滚动重启 → 旧公钥至少保留一个访问令牌有效期。无热加载。

## 质量门禁与测试

- `mvn verify`：全部 `*Test` 与 `*IT`（Testcontainers 拉起 PostgreSQL）、JaCoCo 行覆盖率 ≥ 90%（只排除 `AuthApplication`）、Spotless 格式检查。
- 性能测试 `LoginLoadIT` 标记 `performance`，默认不跑，须关闭 JaCoCo（`-Djacoco.skip=true -Dtest.excluded.groups=none`）。
- 集成测试基类：`PostgresTestBase`（容器、owner 迁移、app 角色运行、每个测试前清库）→ `OAuthTestBase`（授权码流程辅助）→ `AdminApiTestBase`（已登录的管理员与令牌）。
- 架构测试 `ArchitectureTest`：package 无环、禁用标准流与字段注入、含密钥字段的类须自带非 final 的 `toString()`。

## 已知取舍

- 会话查找按用户的会话做哈希比对，会话数极多时可给会话行加一列保存 `sid`。
- 限流进程内，多实例总量放宽；精确限流交给网关。
- 配置与密钥变更靠滚动重启，不做热加载。
- `auth-admin` 被停用后重跑 bootstrap 会因重复登记而失败。
