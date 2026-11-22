# Research: auth

Phase 0 产出。每项给出 Decision / Rationale / Alternatives。无遗留 NEEDS CLARIFICATION；标注“实现时核实”的是版本或坐标级事实，不影响设计。

## D1 技术栈与框架版本

- **Decision**: Java 21、Spring Boot 4.1.x（与 `main` 上 backend 一致）、Maven。授权服务器用 Spring Authorization Server（SAS）；SAS 自 Spring Security 7.0 起并入 Spring Security，不再有独立的 2.x 发行线，坐标仍是 `spring-security-oauth2-authorization-server`，版本随 Spring Security。
- **Rationale**: 用户指定 SAS；Boot 4.1 / Security 7 是 `main` 的既有栈，同仓库内保持一致，避免两套 Security 大版本。
- **Alternatives**: SAS 1.5.x + Boot 3（1.5 是最后一个独立版本，且与 `main` 栈不一致）——拒绝。
- **实现时核实**: Boot 4 的 starter 名（`spring-boot-starter-oauth2-authorization-server` 或带 `security-` 前缀的新名）在官方文档中说法不一，T001 以 Boot 4.1 的 BOM 为准。

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

- **Decision**: 管理 API 与授权服务器同进程，作为资源服务器验证访问令牌，要求 scope `auth.admin`。该 scope 只在“用户是管理员 + client 被允许申请它”时签发；每次请求再查库确认用户仍是启用的管理员。管理 API 只供运维工具使用，通过一个预置的公开 client（`auth-admin`，授权码 + PKCE，回环回调）登录获取令牌。
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
