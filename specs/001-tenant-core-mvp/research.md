# Research: Tenant Core MVP

无遗留 NEEDS CLARIFICATION；以下记录 spec 中 Deferred 数值与关键技术取舍。

## R1 登录需要 Tenant Slug

- **Decision**: 登录表单输入「Tenant Slug + username + 密码」；username 只在 Tenant 内唯一；登录成功后以 Account 所关联的 User 为身份（User 与 Account 分离，见 R12）。
- **Rationale**: spec 允许不同 Tenant 重名；设计 D19 要求 `tenant_id` 由 UAM 配置决定，identity 键为 (Tenant, 类型, 提供方, 主体)。
- **Alternatives**: 全局唯一 username——与 spec 边界情况和多 Tenant 方向冲突；按域名推断 Tenant——属 cell 入口路由，待定且 MVP 不含。
- **影响**: spec 的 FR-008 与 Story 2 需补“Tenant Slug”字样（已同步）。

## R2 密码与锁定策略（spec Deferred）

- **Decision**: Argon2id（Spring Security `Argon2PasswordEncoder`，参数 m=19MiB, t=2, p=1 起步，可配置）；密码长度 12–128，不设复杂度规则，不得等于 username；连续失败 5 次锁定 15 分钟，成功登录清零；锁定中仍返回统一的“用户名或密码错误”，不暴露锁定状态以免枚举 Account。
- **Rationale**: 符合 NIST 800-63B 取向；D22 要求 argon2id + 失败锁定。统一提示满足 FR-010 / SC-006。
- **Alternatives**: bcrypt（较弱的内存硬度）；复杂度规则（体验差、收益低）。
- **注**: spec Story 2 场景 6 要求“给出稍后重试的提示”，与“锁定仍统一提示”存在张力——此处取“统一提示 + 文案包含‘如多次失败请稍后再试’”，既不区分原因又可提示重试。

## R3 不存在 Account 的时序一致

- **Decision**: User 不存在时对固定哑哈希执行一次 Argon2 校验，使响应耗时与密码错误接近。
- **Rationale**: SC-006“不可区分”。

## R4 Session 与 Token

- **Decision**: UAM 签发高熵 Opaque Token（前缀 `tk_`，库内存 SHA-256 哈希）；空闲 30 分钟滑动过期，绝对上限 8 小时。BFF 将 Token 存 Redis session（同样 TTL），浏览器只持 HttpOnly + Secure + SameSite=Lax 的 session cookie。退出时 BFF 删 session 并调用 UAM 吊销 Token。后端判定 Token 失效（401）时，BFF 清除 session 并回到登录页。MVP 无网关，BFF 经请求头 `X-Session-Id` 携带 Opaque Token 调用；UAM 另支持 Internal JWT（见 R13）。
- **Rationale**: 02 文档第 4、6.6 节；BFF 无状态以便水平扩展。
- **Alternatives**: 直接把 Token 放 cookie——违背“Token 不到浏览器”；JWT Session——无法即时吊销。

## R5 首次强制改密

- **Decision**: `uam_credential_password.must_change_password=true` 时，登录成功但 Token 处于“仅可改密/退出/查询自身”状态；其余接口返回 403 `PASSWORD_CHANGE_REQUIRED`，BFF 将 User 导向改密页。改密成功后清除标记，并吊销旧 Token、签发新 Token。
- **Rationale**: D23；服务端强制，不依赖前端。

## R6 CLI 与 API 共用库、直连数据库

- **Decision**: `tenant-core-cli` 引用 `core-lib` 直连 Postgres，不经 HTTP。
- **Rationale**: D23 把 CLI 定位为受控环境（如 Kubernetes Job）的 Bootstrap 工具；避免为 Bootstrap 先有登录才能调用管理接口的鸡生蛋问题；规则复用。
- **Alternatives**: CLI 调管理 API——需先有已登录的 Session，且 MVP 不提供写 API（spec 明确无写 UI/接口之外需求）。

## R7 CLI 初始密码输入

- **Decision**: 默认在 TTY 下隐藏输入并二次确认；非交互场景用 `--password-stdin` 读标准输入（一行）。不提供 `--password` 参数。
- **Rationale**: FR-003、SC-005。

## R8 CLI Audit 的“谁”

- **Decision**: actor 记录为 `cli:<操作系统 username>@<主机名>`；密码与哈希不入 Audit。
- **Rationale**: 受控环境下无登录身份，OS User 是最可得的可追溯信息。

## R9 幂等语义

- **Decision**: 重复执行同一命令返回成功码 0 并提示 `already exists`/`already enabled`/`already subscribed`，不改数据；同标识但属性不同（如 Tenant 同 slug 不同 name）返回冲突错误码并非零退出。Account 重复创建**不会**重置密码。
- **Rationale**: FR-005、SC-004；避免静默覆盖。

## R10 列表分页与排序

- **Decision**: 偏移分页（`page`、`size`，默认 20，最大 100），默认按创建时间倒序、id 次序稳定；响应含 `total`。
- **Rationale**: 数据量小（≤ 数百）；实现最简。

## R12 User 与 Account 分离

- **Decision**: `uam_user` 表示“一个人”（Display Name、`tenant_user_id`），`uam_account` 表示登录凭据（类型、username），Account 关联且仅关联一个 User，一个 User 可有多个 Account；MVP 每 User 只建一个密码 Account（部分唯一约束）。密码、锁定、首次改密标记都挂在 Account 的 Credential 上。Token 记录登录所用 Account 并带出 User。
- **Rationale**: 与设计 D17/D19 的 identity 模型一致，后期加 OIDC、微信 Account 无需改 User 表与下游（下游只认 `tenant_user_id`，D8）。
- **CLI 幂等**: User 没有天然唯一键，MVP 以 (Tenant, Display Name) 在 CLI 逻辑中去重（不加数据库约束，避免阻碍后期同名 User）；代价是 CLI 无法为同 Tenant 创建两个同名的人，Bootstrap 场景可接受。

## R13 同时支持 Opaque Token 与 Internal JWT（已决定）

- **Decision**: UAM 提供统一的请求凭据校验入口，由请求头决定：`X-Session-Id` 携带 Opaque Token → 查表；`Authorization: Bearer` 携带 Internal JWT → 本地验签（ES256，只接受配置的 `iss` 与 `aud`，拒绝 `alg=none`/HS256/未知 `kid`）。同时提供 `POST /v1/auth/exchange`（Opaque Token → Internal JWT，默认 120 秒）与 `/.well-known/jwks.json`。登录、退出、改密、换发只接受 Opaque Token；两个头同时出现、都缺失或放错位置（Bearer 里放 Opaque Token、`X-Session-Id` 里放 JWT）一律 401；`tk_` 前缀仅用于泄漏扫描，不参与路由；要求改密的 Session 不可换发。JWT 不含 `scope`，也不含改密状态。
- **Rationale**: 02 文档 D18 的基线是“网关 ext_authz 换发 JWT，各服务本地验签”。MVP 不部署网关，但把换发与验签做在 UAM 里，以后引入网关只需让 ext_authz 调换发接口，下游不改；并让契约与测试从第一天就覆盖真实的 JWT。
- **Alternatives**: 只做 Opaque Token（延后 JWT）——已明确要求同时支持；把验签放到独立库——前期合并部署（D29），不必拆。
- **密钥**: 私钥只来自环境变量/挂载文件，不入库；`dev` profile 才允许临时密钥；单把活动密钥，轮换（重叠期）留到后期。
- **吊销延迟**: 退出后不能再换发，但已换发的 JWT 在 `exp` 前仍有效（设计基线认可的延迟）。

## R14 单 schema、表前缀与 package 拆分（已决定）

- **Decision**: 只用 `public` 一个 schema；package 边界靠 Java 包和表名前缀（`core_`、`uam_`、`audit_`）；package 之间不建外键、不联表，跨 package 数据在服务层经接口组装。
- **Rationale**: 前期单进程单库，不为尚未拆分的服务引入多 schema 与多角色；前缀与“无跨 package 外键”保证以后能按前缀把表迁出。
- **例外**: `audit_event` 对应用角色撤销 UPDATE/DELETE/TRUNCATE，保持只追加。

## R11 本地开发与测试基础设施

- **Decision**: `deploy/docker-compose.yml` 提供 Postgres 与 Redis；后端集成测试用 Testcontainers，前端 E2E 用 Compose 起依赖。
- **Rationale**: 与 Kubernetes 目标一致且无需手工安装。

## R15 升级到 Spring Boot 4.1（用户决定）

- **Decision**: 父工程用 `spring-boot-starter-parent` 4.1.2（Maven Central 上的最新稳定版；4.2.0 仍是预览版）。核心依赖由 Boot 管理：Flyway 12.4、Testcontainers 2.0.5、JUnit 6.0.3、Spring Security 7.1.2、Jackson 3.1。Nimbus JOSE JWT、picocli、BouncyCastle 仍由我们自己固定版本。
- **Rationale**: 保持在受支持的主线上；Java 21 满足基线（17+）。
- **风险与对策**: 起步依赖与测试库坐标、包名变化，`TestRestTemplate` 与 Jackson 3 的包名变化。现有 108+25+24 个后端测试和 API 契约测试就是回归网；升级后必须先全绿再继续，并新增断言确认 `Instant` 仍序列化为 ISO-8601 字符串。
- **Alternatives**: 留在 3.5.x（用户已明确要求 4）。

## R16 shadcn/ui + Tailwind CSS 4 与自适应界面（用户决定）

- **Decision**: 用 shadcn/ui（CLI 4.x）生成 Button、Input、Label、Card、Table、Badge、Alert、Sheet、Separator 到 `src/components/ui`，样式用 Tailwind CSS 4。布局：`md`（768px）以上左侧导航，以下顶部栏 + Sheet 折叠菜单；列表 `sm`（640px）以上 Table，以下卡片；登录/改密页居中 Card。状态用 Badge（正常/已暂停/已停用/已订阅）；时间用客户端组件按本地时区渲染，服务端输出 `<time dateTime>` 的 UTC 文本。
- **Rationale**: 组件源码在仓库内、可按术语表命名与测试；Radix 提供可访问性；用 Tailwind 断点即可，不再手写媒体查询。
- **测试**: jsdom 不计算 CSS 媒体查询，组件测试验证 DOM 结构与断点类名；真实的“无横向滚动、菜单可用”由 Playwright 在 360/768/1280 三种视口验证。
- **Alternatives**: 手写 CSS（用户要求组件库）；其他组件库（用户指定 shadcn/ui）。

## R17 PostgreSQL 18（用户决定）

- **Decision**: 本地 Compose 与 Testcontainers 都用官方镜像 `postgres:18`（当前 18.1）。
- **关键变化**: 18 的镜像把数据放在 `/var/lib/postgresql/18/docker`，因此 Compose 数据卷挂载点改为 `/var/lib/postgresql`；`deploy/initdb/01-roles.sh` 与迁移 SQL 不受影响（只用标准 SQL 和 `gen_random_uuid()`）。
- **验证**: 现有 `SchemaConstraintsIT`（约束、外键边界、审计只追加权限）在 18 上全绿即为通过；不使用 18 的新特性。

## R18 ArchUnit 架构规则（用户决定）

用 ArchUnit 的 `archunit-junit6` 1.5.1（test scope）声明规则（`@AnalyzeClasses`、`@ArchTest`，测试类以 `*Test` 结尾，随 `mvn test` 运行），把已定的设计变成可执行规则：

| 位置 | 规则 |
|---|---|
| core-lib `ArchitectureTest` | ① `tenant`、`uam`、`audit`、`common` 之间无环；`tenant` 不得依赖 `uam`（D33）；`audit`、`common` 不依赖业务 package ② `*Repository` 只被所在 package 与 `CoreComponents` 访问 ③ core-lib 不依赖 `org.springframework.web`、`jakarta.servlet`、`picocli` 与 API、CLI 的 package ④ 不访问 `System.out/err`、`java.util.logging`，不用字段注入 ⑤ 术语：类名含 `Module` 的只能是 `ModuleSubscription*`；类名不得出现同义词（Customer、Organization、Member、Profile） ⑥ 持有 password、token、jwt、hash、pem、secret 字段的类必须自己声明 `toString()`（防止 record 默认的 `toString` 把秘密打进日志） |
| API `ApiArchitectureTest` | Controller 与过滤器只依赖 `CoreComponents` 与自身 DTO，不访问任何 `*Repository`；Controller 只在 `..api..`；API 不访问标准输出 |
| CLI `CliArchitectureTest` | `..cli.commands..` 不访问 `*Repository`，不依赖 `org.springframework.web`；只有 `TenantCoreCliMain` 与 `PasswordReader` 可访问 `System.in/out/err/console` |

- **预期的整改**: 规则⑥会要求给 API 的 `LoginBody`、`ChangePasswordBody`、`LoginResponse`、`ExchangeResult` 等 DTO，以及 core-lib 的 `IssuedToken`、`IssuedJwt` 补 `toString()`，这是有意的加固。
- **依据**: ArchUnit 1.5.0 的官方 Release Notes 写明 “`archunit-junit6` supports ArchUnit with JUnit 6”（issue #1556），Maven Central 上有 `archunit-junit6` 1.5.1。升级后以测试实际运行为准。
- **Alternatives**: `archunit-junit5`（针对 JUnit 5，与 Spring Boot 4 的 JUnit 6 不配套）。

