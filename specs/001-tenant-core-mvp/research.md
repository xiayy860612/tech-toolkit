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
