# Research: Tenant Admin Center

**Feature**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md)

仓库当前无任何代码与既有技术选型（`docs/achieve-specs/`、`docs/system-design/` 均不存在），本文为首期技术决策基线。`spec.md` 中已无 `NEEDS CLARIFICATION`，以下条目均为技术取舍。

> 后端（R1）与前端（R2）技术栈均已按用户提供的依赖清单确定；清单之外的增减项在各条目中明示。

## R1. 后端语言与框架

- **Decision**: 按用户提供的 Maven 清单：Java 21 + Spring Boot 4.1.2（`spring-boot-starter-parent`），**Maven 单模块**（groupId `tech.toolkit.tenantcore`，用户明确不使用多模块）；Flyway 迁移；Bouncy Castle `bcprov-jdk18on`（Argon2id 所需）；`nimbus-jose-jwt`（内部 JWT 校验）；ArchUnit（`archunit-junit6`）约束包级分层；JaCoCo 行覆盖率 ≥ 90%（`verify` 阶段 check，仅排除 `*Application` 入口类）；Spotless + google-java-format 在 `verify` 阶段检查；surefire 同时运行 `*Test` 与 `*IT`。
- **清单之外的取舍**: 不使用多模块；采用 `nimbus-jose-jwt` 校验 BFF 签发的内部 JWT（R16）；未采用 `picocli`——`bootstrap` 以同一应用的非 Web 运行模式实现（见 R14）。
- **Rationale**: 与用户既有后端栈一致；单模块降低构建与发布复杂度，本特性规模不需要模块边界；Spring Security 对服务端会话、CSRF、密码编码开箱即用；Testcontainers、JaCoCo 便于达成 90% 覆盖率门禁。
- **Alternatives**: 多模块（core-lib / api / cli）——边界更硬但此规模收益不足，已被用户否决；Gradle、Node（NestJS）、Go——与既有栈不一致。

## R2. 前端

- **Decision**: 按用户提供的前端依赖清单：Next.js 15（App Router）+ React 19 + TypeScript，Node ≥ 22；Tailwind CSS 4 + shadcn 风格组件（radix-ui、class-variance-authority、clsx、tailwind-merge、lucide-react、tw-animate-css）；测试 Vitest + Testing Library + jsdom（覆盖率 v8）+ Playwright；ESLint + Prettier + `tsc --noEmit`。在清单之外增加 `zod`（表单校验，复用与后端一致的规则）与 `openapi-typescript`（由契约生成类型，FR-024）；**采用** `ioredis`（BFF 限流计数存储，测试用 `ioredis-mock`）并**新增** `jose`（BFF 签发内部 JWT）；未采用 Lighthouse/puppeteer 性能脚本（首期不设该门禁，需要时再加）。
- **数据流**: 浏览器的所有数据请求发往 BFF 的 `/api/v1/*` 路由处理器；处理器从 Cookie 取会话令牌 → 校验来源（非 GET）→ 经后端 resolve（短 TTL 缓存）取得会话身份 → 签发内部 JWT → 转发后端 → 回传响应。服务端组件读取数据时走同一个服务端后端客户端。登录、登出由 BFF 专用路由处理（设置/清除 Cookie、限流）；改密、会话管理等其余接口均为纯代理。不引入 TanStack Query / React Hook Form。`middleware.ts` 仅在缺少会话 Cookie 时跳转登录页，属体验优化，**鉴权以后端为准**。
- **Rationale**: 与用户既有前端栈一致，复用其团队工程习惯与组件范式；shadcn/Radix 提供可访问的 Dialog/Switch/Select，贴合设计稿的可访问性要求；Tailwind 4 的 `@theme` 适合承载设计 token。
- **与"前后端分离"的关系**: Next.js 作为独立容器单独构建部署，只通过 `contracts/openapi.yaml` 访问后端，不直连数据库、不持有业务逻辑，满足 FR-024。
- **Alternatives**: Vite SPA——更轻、无前端 Node 运行时，但与用户既有栈不一致；Vue/Ant Design 等整套组件库——会偏离设计稿视觉。

## R3. 数据库与多租户隔离

- **Decision**: PostgreSQL 16；**共享库、共享 schema，所有业务表带 `tenant_id`**。双层隔离：
  1. 应用层：每个请求在事务内设置租户上下文（来自会话），仓储查询一律带租户条件；
  2. 数据库层：对业务表启用 **Row-Level Security**，策略为 `tenant_id = current_setting('app.tenant_id')::bigint`；应用使用非超级用户、非表属主角色连接，无法绕过。
- **Rationale**: SC-003 要求跨租户 0 泄露，单靠"记得加 where"不够；RLS 让遗漏条件时返回空而非串租户。规模（≈1,000 User/租户）无需分库分 schema。
- **Alternatives**: schema-per-tenant / DB-per-tenant——隔离更强但迁移、连接池、运维成本高，超出首期；仅应用层过滤——有人为遗漏风险。
- **本期租户**: 不涉及租户管理，所有用户 `tenant_id = 0`（`bigint`）；**不建 `tenant` 表**，全部业务表带 `tenant_id` 并启用 RLS。隔离能力现在就实现，并用测试夹具中的另一租户（id=1）验证（SC-003）。

## R4. 会话机制（后端统一维护会话 + BFF 持会话令牌 + 内部 JWT）

- **Decision**: 会话由**后端（UAM）统一维护**——`session` 表（带 `tenant_id`，启用 RLS）。登录成功时后端生成 256 位随机不透明令牌（只存 SHA-256 哈希），**一次性**返回给 BFF；BFF 以 `HttpOnly; Secure; SameSite=Lax` Cookie 下发，**浏览器与 BFF 之间只有这个会话令牌**。空闲 30 分钟（滑动，`last_seen_at` 每分钟最多写一次）、绝对 8 小时，均在后端配置。
- **请求流程**: BFF 收到请求 → 调后端 `POST /auth/sessions/resolve`（`typ=service` JWT，body 带令牌）取得 `sid`、`userId`、`tenantId`、`mustChangePassword` 等（BFF 可在内存缓存该结果 ≤ `SESSION_RESOLVE_CACHE_TTL_SECONDS`，默认 5 秒，0 关闭）→ 签发 `typ=user` JWT（`sub`、`tenant_id`、`sid`、`cip`）→ 调用后端。**后端每次 API 调用都按 `sid` 校验会话有效**（未撤销、未过期、与声明一致）并核对 User 状态，所以撤销立即生效；缓存只作用于 resolve 层，不放大撤销延迟。
- **撤销**: 全部在后端同一事务内完成并写审计——退出登录、User 停用、重置密码、修改密码（保留当前会话）、管理员强制下线。因此不需要 BFF 清理钩子，也不需要 `session_version`。过期/已撤销会话由后端定时任务清理（保留期默认 7 天，可配置）。
- **多 BFF**: 将来 Biz BFF、Platform BFF 复用同一套"resolve → 签发 JWT → 调用"流程；会话统一在后端，管理员的会话视图与强制下线对所有 BFF 生效。
- **Rationale**: 用户决定——后期会有多个 BFF，会话维护在后端服务中。会话是安全关键状态，集中在 UAM 便于租户隔离（RLS）、审计与统一撤销。
- **Alternatives**: 会话存各 BFF 的 Redis——多 BFF 间无法统一查看/撤销（此前方案，已否决）；浏览器直接持有 JWT——吊销困难；每个请求都由后端按令牌鉴权而不要 JWT——与"BFF 与后端以携带用户与租户的内部 JWT 通信"的约束冲突。
- **代价**: 每个请求多一次 resolve（可缓存）；后端承担会话表读写（续期写入有节流）。

## R5. CSRF 与跨域

- **Decision**: 浏览器只访问 BFF 的域名，后端**不对浏览器暴露**（仅内网可达），因此**不开 CORS**，后端也**无 CSRF 面**。BFF 对所有非 GET/HEAD 请求校验 `Origin`（与自身来源一致）与 `Sec-Fetch-Site`（`same-origin`），不满足一律 403；Cookie 另加 `SameSite=Lax`。`/api/v1/*` 由 BFF 的路由处理器代理到后端（需要在转发时附加 JWT，故**不使用 Next.js rewrites**）。
- **Rationale**: Cookie 会话必须防 CSRF；来源校验 + SameSite 对同源后台 SPA 足够，且不需要在前端维护令牌。
- **Alternatives**: 双提交 CSRF 令牌——可行但增加前端状态；异站 + CORS + `SameSite=None`——暴露面更大。

## R6. 密码存储与策略

- **Decision**: Argon2id（Spring Security `Argon2PasswordEncoder`，依赖 Bouncy Castle），参数按登录 p95 ≤ 1 秒调优并随哈希存储以便升级；复杂度规则：长度 ≥ 10，含大小写、数字、符号中至少 3 类，且不得含用户名、不得等于临时密码（FR-011/011a）。临时密码由系统生成时为 16 位随机，只在响应中返回一次。
- **Rationale**: Argon2id 为当前推荐；FR-006 要求不可逆存储且不记录明文。
- **Alternatives**: BCrypt——可用但对 GPU 抗性较弱；PBKDF2——FIPS 场景才优先。

## R7. 租户确定与登录防护

- **Decision**: 本期**不涉及租户管理**：登录页没有租户入口，所有登录归入默认租户 `tenant-id = 0`，由 **BFF 的环境变量 `DEFAULT_TENANT_ID`（默认 0）**提供。BFF 调用后端登录时，把该值放进 `typ=service` 内部 JWT 的 `tenant_id` 声明——后端依旧只信已验签 JWT 里的租户，登录请求体不含任何租户字段。
- **扩展点（FR-015）**: 租户确定方式（租户编码、子域名等）属于租户管理特性；届时只需让 BFF 在登录前解析出 tenant-id 并写入该声明，登录之后的认证、会话、隔离逻辑不变。首期不预留额外抽象（YAGNI）。
- **登录防护**: 登录接口由 BFF 按 IP 限流（Redis，见 R7）；对"用户名不存在、密码错误、账号停用、被锁定"统一返回 401 `INVALID_CREDENTIALS`。

## R8. 登录锁定（FR-008/008a）

- **Decision**: 在 `app_user` 上记录 `failed_attempts`、`locked_until`（用户名密码是 User 自身的一部分）。连续失败达阈值（默认 5）→ `locked_until = now + 15min`；成功登录清零；锁定期间即使密码正确也拒绝，对外仍返回统一的 401 `INVALID_CREDENTIALS`。阈值/时长走配置。管理员可手动解锁（清零并清除 `locked_until`），两类事件写审计。
- **防骚扰**: 锁定为临时且自动解锁；锁定针对用户而非 IP，同时叠加 IP 限流以限制枚举。
- **对不存在的用户名**: 仍执行等价的哈希比较（恒定耗时的哑哈希），避免通过耗时区分用户名是否存在（FR-007）。

## R9. 用户名密码内置于 User，其他登录方式用 Account 扩展（SC-007）

- **Decision**: 用户确认「每个用户必然有 username password」「用户名密码绑定在 user 身上，后续其他的 Account 用各自的表同 user 进行关联」。因此用户名密码**不是 Account**：`app_user` 直接持有 `username`、`username_normalized`、`password_hash`、`must_change_password`、`failed_attempts`、`locked_until`、`last_login_at`（均随 User 创建、`NOT NULL` 保证必然存在）。**Account 只表示其他登录方式**（手机号、微信、OAuth2.0…），每种类型一张自己的表，带 `user_id`，以 `UNIQUE (tenant_id, user_id)` 保证每个 User 对每种类型至多一个；首期没有任何此类表。登录经 `Authenticator` 策略接口（首期仅 `PasswordAuthenticator`，直接读 `app_user`），新增类型 = 新表 + 新 Authenticator，**不改 `app_user`**。
- **唯一性**: `UNIQUE (tenant_id, username_normalized)`，用户名规范化为 NFKC + 小写，租户内大小写不敏感唯一（FR-014）。
- **主键**: `app_user`、`audit_log` 用 `bigint GENERATED ALWAYS AS IDENTITY`（FR-004a）；会话 id 保持 UUID——它是对外公开的标识，用户确认"除会话外"，也避免被枚举。JWT 的 `sub` 为整数，`sid` 为 UUID。
- **会话记录登录方式**: `session.login_type`（首期 `USERNAME_PASSWORD`）取代原 `account_id`，日后随 Account 类型扩展。
- **Alternatives**: 把用户名密码仍建模为一种 Account（原方案，需要 `account` 表、"至多一个"唯一约束、最后一个 Account 确认、停用/删除自己当前 Account 的守卫等一整套规则，而"必然有"使其成为无谓的复杂度）；单表 + 所有类型的可空凭证列——类型一多就膨胀。

## R10. 审计日志（FR-023）

- **Decision**: `audit_log` 追加写；对应用角色仅授予 `INSERT, SELECT`（无 `UPDATE/DELETE`），并加触发器兜底拒绝更新/删除。记录 `tenant_id、actor_user_id、actor_account_id、action、target_type、target_id、result、reason、ip、occurred_at、detail(jsonb，已脱敏)`。登录成功/失败、锁定/解锁、全部管理操作经统一切面/应用服务写入，与业务在**同一事务**内提交（失败登录也落审计，独立事务）。
- **查询**: 后端提供 `GET /audit-logs`（管理员、本租户）；界面首期不做（spec Assumptions）。

## R11. 并发与一致性

- **Decision**: `app_user` 带 `version`（JPA `@Version`）。更新类接口请求体携带 `version`，冲突返回 409 `VERSION_CONFLICT`，前端提示刷新（edge case：两管理员同时编辑）。"最后一名管理员"的守卫在事务内以 `SELECT … FOR UPDATE` 锁定相关行后再判断，避免并发绕过（FR-021）。

## R12. API 风格与错误模型

- **Decision**: REST + JSON，`/api/v1` 前缀，OpenAPI 3.1 契约先行；错误采用 RFC 9457 `application/problem+json` 并附稳定的 `code` 字段供前端映射；分页 `page`（从 0 起）+ `size`（≤100），响应含 `total`。时间一律 RFC 3339 UTC，id 为 UUID。
- **契约测试**: 后端集成测试用 OpenAPI 校验器断言真实响应符合契约；前端 API 类型与测试桩由同一契约生成（openapi-typescript），契约变更须先改契约与失败用例（根 AGENTS.md「契约优先」）。

## R13. 测试策略与覆盖率门禁

- **Decision**:
  - 后端：领域逻辑单元测试；仓储/RLS/迁移/会话用 Testcontainers 集成测试；**跨租户隔离测试集**（覆盖 SC-003 列出的全部操作与直接构造请求）；ArchUnit 守住包级分层（业务包不依赖 `api`；`bootstrap` 与 `api` 互不依赖）；JaCoCo 行覆盖率 ≥ 90%。
  - 前端：组件/Hook 用 Vitest + Testing Library；Vitest v8 覆盖率 ≥ 90%；E2E（Playwright）仅覆盖：登录→强制改密→创建用户→新用户登录、重置密码与解锁、会话强制下线。跨租户不可见由后端隔离测试集覆盖（本期只有租户 0，租户 1 为测试夹具）。
  - 排除路径（生成的 API 类型、入口/启动类、纯配置）在各自 `AGENTS.md` 明示。
- **Rationale**: 满足根 AGENTS.md「测试先行」与 Quality gates；E2E 控制在关键旅程避免慢而脆。

## R14. 部署与配置

- **Decision**: 后端容器（**仅内网可达**）+ Next.js BFF standalone 容器（唯一对外入口）+ PostgreSQL + Redis；BFF 通过环境变量获得后端地址、Redis 地址与内部 JWT 私钥，后端只持有对应公钥。配置全部走环境变量（后端：会话时长、锁定阈值等；BFF：后端地址、限流等），仓库只放 `.env.example`；密钥与私钥文件不入库（开发密钥由脚本生成到被忽略的目录）。
- **预置首位管理员**: 同一 Spring Boot 应用的 `bootstrap` 非 Web 运行模式（以启动参数/profile 触发，复用同一套领域服务，运行完即退出）在默认租户（`tenant_id = 0`）创建首位 `TENANT_ADMIN`，接受管理员用户名，临时密码由系统生成并只在终端输出一次，首次登录强制改密；不创建租户（租户管理不在本特性范围）。开发 profile 提供明示的测试种子数据；测试 profile 另预置租户 1 的夹具仅用于隔离测试。

## R15. 界面设计参考（用户提供）

- **来源**: Claude Design 画板「后台管理页面设计」，含 4 个画板：登录、数据总览（Dashboard）、列表页（筛选 + 表格 + 分页）、表单页（分组表单 + 吸底操作栏）。
- **提取的设计语言**: 深海军蓝侧边栏 `#0F1629`（宽 232px）+ 浅灰蓝画布 `#F4F6FA` + 白色圆角 12px 卡片（边框 `#E2E6EF`）；主色 `#2F54EB`；字体 Inter + 苹方/微软雅黑；正文 14px、页标题 24px/600；输入框与按钮高 40–44px（触控 ≥ 44px）；状态徽标用淡底深字胶囊；表格为 grid 行、表头 `#F8F9FC`；分页按钮 44px；表单分组卡片 + 吸底操作栏；表单错误用红色边框 + 图标文案并带 `aria-invalid`/`aria-describedby`。
- **Decision**: 复用其视觉语言与页面骨架，**按 spec 裁剪**（去掉 SSO/扫码/忘记密码/部门/数据权限/订单商品等与本期无关的内容）。完整映射与差异见 [contracts/ui-design.md](contracts/ui-design.md)。
- **注意**: 设计稿的分享链接带访问密钥，文档中只记录画板标题与不含密钥的地址。

## R16. BFF → 后端的内部 JWT

- **Decision**: BFF 为每次后端调用现签一个短期 JWT（有效期 60 秒，`jose`）。**非对称签名 EdDSA（Ed25519）**：BFF 持私钥，后端仅持公钥并用 `nimbus-jose-jwt` 校验（支持密钥轮换：`kid` + 公钥集合）。后端只接受白名单算法，拒绝 `none` 与对称算法。
- **声明**: `iss=admin-center-bff`、`aud=tenant-core-api`、`jti`、`iat`、`exp`；`typ=user` 时含 `sub`（整型 userId）、`tenant_id`（数字，本期恒为 0）、`sid`（后端会话 id）、`cip`（可信客户端 IP）；**不携带 `role`**——授权所需角色以数据库当前值为准（FR-022）；`typ=service` 用于登录与会话解析，含 `iss/aud/jti/iat/exp/cip` 与 `tenant_id`（默认租户），**不含**用户声明。
- **校验与授权**: 后端过滤器验签、验 `iss/aud/exp`（允许 ≤ 30 秒时钟偏差）后，从 `tenant_id` 设置租户上下文（FR-025：租户只取自已验签的 JWT，忽略请求头/参数/请求体中的任何租户标识），再校验 `sid` 对应会话有效且与 `sub/tenant_id` 一致（R4），核对 User 为 `ACTIVE`，并在同一次查询中取出用户当前角色作为角色闸门依据（FR-022）。`typ=service` 令牌只能访问 `/auth/login` 与 `/auth/sessions/resolve`；用户端点必须是 `typ=user`。
- **Rationale**: 满足"后端不直接信任浏览器、租户与用户由可信的 BFF 断言"；短期 + 非对称让后端无需共享签名密钥，被窃取的令牌窗口极小；后端每次按 `sid` 校验会话与库内状态，补足 JWT 无法主动吊销的缺口。
- **Alternatives**: 对称 HS256 共享密钥——后端泄露即可伪造；mTLS 单独认证——不携带用户/租户声明，仍需另行传递；在 Header 中明文传 `X-User-Id/X-Tenant-Id`——无完整性保护。

## R17. 平台分层中的定位与租户隔离规则

- **输入**: 平台分层架构图——入口层（Tenant Admin BFF / Biz BFF / Platform BFF / API Gateway）、租户级业务服务（UAM）、平台服务（Tenant Manager / App Manager）、基础服务（Audit / Scheduler / Notification）、Event Bus；规则：**平台服务不区分租户，不做租户隔离；其余业务服务与基础服务必须做租户隔离**。另：**本特性不涉及租户管理，租户固定为 `tenant-id = 0`**。
- **Decision**: 本特性后端即 **UAM**，前端即 **Tenant Admin BFF**。
  - UAM 全部数据做隔离（R3 的 RLS + R16 的 JWT 租户），`tenant_id` 为 `bigint`，本期恒为 0。
  - **租户本身属平台服务（Tenant Manager）的范畴，本特性不建模**：没有 `tenant` 表、没有租户查询/概览接口、没有租户创建；将来接入 Tenant Manager 时，租户状态（启用/停用）可经 Event Bus 事件或调用获得。
  - **审计**经 `AuditSink` 端口写入；首期实现写本库 `audit_log`（带 `tenant_id` + RLS，满足"基础服务必须隔离"）；日后 Audit 服务落地时，换成发布审计事件到 Event Bus，业务代码不变。
  - Notification、Scheduler、App Manager、Event Bus 首期不接入；会话过期清理由后端定时任务完成（首期进程内 `@Scheduled`，日后可迁到 Scheduler 基础服务）。
- **Rationale**: 不提前实现其他服务（YAGNI），但保证"该隔离的一定隔离"，并让租户管理将来能以最小代价接入。
- **Alternatives**: 首期就建 `tenant` 表与租户入口——超出本特性范围；省略隔离、只用单租户——违背"业务服务必须隔离"的项目规则，日后改造代价大。

## R18. 登录会话管理（独立「会话管理」页）

- **背景**: 会话由后端统一维护（R4），管理员需要在独立页面查看本租户全部在线会话并强制下线（FR-026～030）。
- **Decision**: 后端提供会话管理接口，**BFF 纯代理**：`GET /sessions`（本租户、用户关键字、分页）、`DELETE /sessions/{sessionId}`（强制下线单个）、`DELETE /users/{userId}/sessions`（下线该用户全部）。授权（数据库中的 `TENANT_ADMIN` 角色）、租户隔离（RLS）、撤销与审计都在后端同一事务内完成。
- **展示字段**: `id`（会话 UUID，**不是**令牌，可安全暴露）、用户 id、显示名与用户名、登录方式、设备摘要（后端从 User-Agent 解析为「浏览器 / 系统」）、客户端 IP、登录/最近活跃/到期时间、`current`（是否调用者当前会话，依据 JWT 的 `sid`）。**绝不返回**令牌、令牌哈希或原始 User-Agent。
- **规则**: 当前会话不可下线（409 `CANNOT_REVOKE_CURRENT_SESSION`）；对自己的用户不提供"下线该用户全部会话"（409 `CANNOT_REVOKE_OWN_SESSIONS`）；`DELETE /sessions/{id}` 对不存在/已过期/其他租户的会话一律 204（幂等且不泄露存在性）；`DELETE /users/{id}/sessions` 对不存在或其他租户的用户 404；审计 `SESSION_REVOKED` / `SESSIONS_REVOKED_ALL` 与撤销同事务提交，不会出现"已下线但无审计"。
- **列表保持准确**: 用户停用、改密、重置密码都由后端同事务撤销会话，列表只返回有效会话，天然准确；无需 BFF 清理钩子。
- **Rationale**: 会话与审计、隔离同处一个服务，没有跨服务的一致性问题；多个 BFF 共用同一套管理接口。
- **Alternatives**: 会话管理放在 BFF——与"会话在后端"的决定冲突；放进用户详情页——已被用户否决。
