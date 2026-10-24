# Admin Center 用户管理与会话管理（UAM）— 需求与领域现状归档

> **归档状态**：特性 `002-admin-center-uam`（分支 `feature/admin-and-uam`）已交付并归档。本文是**当前真实状态**的权威叙述（已落地的需求、领域与行为）；架构与实现见 [docs/system-design/admin-center-uam.md](../system-design/admin-center-uam.md)。
> 过程记录（调研、澄清、方案、任务拆分）保留在 [specs/002-admin-center-uam/](../../specs/002-admin-center-uam/)：`spec.md`、`plan.md` 是**过程记录**，与本文冲突时**以本文为准**（实现与原计划有偏差的地方见 §9）。
> 本文只记录能在代码、测试、脚本或文档中核实的内容；未核实或未测量的项目在 §7、§8 明确列出。

## 1. 范围与角色

| 服务 | 目录 | 角色 |
|---|---|---|
| **Admin Center** | `apps/admin-center` | 接入层 Web Server（Next.js SSR）。登录页、User Manager、Session Manager；浏览器对它的访问**基于会话**（cookie）。无状态，**不存密码、不连数据库**。它是 UAM 的**客户端（Client）**。 |
| **UAM** | `apps/uam` | RESTful 领域服务（Spring Boot），用户、账号、会话的**唯一权威**。**不基于用户会话**：不使用 cookie、会话标识（仅作 `verify` / `logout` 的输入数据）、JWT 或任何用户凭据；只识别**调用它的服务**（静态服务密钥）。 |

契约：UAM 的唯一权威契约是 [`contracts/uam/openapi.yaml`](../../contracts/uam/openapi.yaml)；Admin Center 的路由、cookie、文案契约是 [`specs/002-admin-center-uam/contracts/web-routes.md`](../../specs/002-admin-center-uam/contracts/web-routes.md)。

**本期功能**：账号名 / 密码登录（多账号识别为同一用户）；User Manager（列表、搜索、详情、新建用户、停用 / 启用）；Session Manager（在线用户、用户会话、强制下线）；修改自己的密码；退出与会话自然失效；自适应（320–2560）；横向扩展；服务密钥（权限、不停机轮换）；已确认的界面设计原型。

**本期不做**（范围外，设计不阻碍日后加入）：第三方 / OIDC 登录、短信 / 邮件验证、其他账号种类的实际实现、权限与角色、可查询的审计记录与界面（仅写应用日志）、编辑 / 删除用户、重置他人密码、忘记密码找回、为已有用户绑定 / 解绑账号、单个账号的停用 / 启用 / 删除、批量下线、更强的服务身份（mTLS 等）。

## 2. 统一语言（Ubiquitous Language）

| 术语 | English | 含义 |
|---|---|---|
| 用户 | User | 人的资料；状态 `ACTIVE`（正常）/ `DISABLED`（已停用）；名称 1–64 字符、**不要求唯一**。表 `app_user`。 |
| 账号 | Account | 登录主体，隶属于恰好一个用户（用户 : 账号 = 1 : n）；本期只有种类 `PASSWORD`（账号名 / 密码）；状态 `ACTIVE` / `DISABLED` / `DELETED`（后两者本期没有接口会写入，登录判定按「非 ACTIVE 即不可登录」）。账号名（`loginName`）创建后不可改，规范化（小写）后**全局唯一**。 |
| 凭据 | Credential | 密码账号的密码哈希（Argon2id，`DelegatingPasswordEncoder` 格式，可自动升级）；明文永不落库、不进日志。表 `credential`。 |
| 会话 | Session | 账号一次成功登录产生的登录态，**与账号一对一**，通过账号隶属于唯一的用户。存于 Redis。 |
| 会话标识 | Session ID | **机密**。256 位随机数（base64url），只存在于浏览器 cookie 与 Admin Center ↔ UAM 的 `verify` / `logout` / `login` 响应里；Redis 只存其 SHA-256。 |
| 会话引用 | Session Ref | **非机密**的 UUID，页面、接口、日志里唯一的会话名称。 |
| 会话数据 | Session Data | `verify` / `login` 返回的普通数据：用户（id、名称）、账号（id、账号名、种类）、会话引用、到期时间。**不是凭据**，浏览器拿不到。 |
| 客户端 | Client | 调用 UAM 的**服务**（不是浏览器）；有名称、1–2 把服务密钥、权限。部署配置（`UAM_CLIENTS`），不入库。本期只有 `admin-center`。 |
| 服务密钥 | Service Key | 客户端的静态长随机密钥（`uamk_…`，32 字节随机、base64url）；UAM 只存其 SHA-256（`sha256:<hex>`）。 |
| 断言权限 | `assert-end-user-context` | 客户端「可代用户断言操作人与来源地址」的权限；本期唯一的权限，只有 `admin-center` 持有。 |
| 操作人上下文 | Operator Context | 发起操作的运维人员（用户、账号、会话引用），由客户端以 `X-Operator-User-Id` / `-Account-Id` / `-Session-Ref` 请求头传入；UAM **信任**它，只用于自我保护规则、`current` 标注与写操作日志，不用于认证或授权。 |
| 运维人员 | Operator | 使用 Admin Center 的人，即一位已登录的用户；本期**不分角色**，任何已登录用户可执行全部写操作。 |
| 在线用户 | Online User | 至少有一个未过期、未终止会话的用户。 |
| 会话三态 | Session State | Admin Center 对每个请求的判定：`authenticated` / `anonymous` / `unverified`（见 §5.2）。 |
| 幂等键 | Idempotency Key | 新建用户的请求头 `Idempotency-Key`（16–64 位可打印 ASCII），保证双击 / 重试不产生两个用户。 |

## 3. 领域模型

### 3.1 聚合与存储

| 聚合 | 根 | 存储 | 一致性边界 |
|---|---|---|---|
| User 聚合 | `User`（含 `Account` 0..*、`Credential` 每个密码账号一条） | PostgreSQL | 新建用户时「用户 + 账号 + 凭据」同一事务；停用 / 启用只改 `User`。 |
| Session 聚合 | `Session` | Redis | 只以 `accountId` / `userId` 引用 User 聚合，**无外键**；一致性靠用例保证（停用后终止会话、登录后复查用户状态）。 |
| 请求上下文与配置 | `OperatorContext`、`ClientIdentity` | 请求头 / `UAM_CLIENTS` | 不是业务数据：`ClientIdentity` 决定「谁能调」，`OperatorContext` 说明「代谁调」。 |

### 3.2 不变量

| 不变量 | 由谁保证 | 需求 |
|---|---|---|
| 账号名规范化后全局唯一（含已停用用户的账号）；账号名创建后不可改 | PostgreSQL 唯一索引 `uq_account_login_name_norm` + `CHECK (login_name_norm = lower(login_name))` + `AccountNameRules` | FR-022 |
| 同一账号任一时刻至多一个有效会话，新登录顶掉旧会话 | `login.lua`（原子「读旧 → 删旧 → 写新 → 更新索引」） | FR-004 |
| 账号能登录 = 账号 `ACTIVE` 且所属用户 `ACTIVE`；失败统一提示，**先哈希比对再判状态**，账号不存在时比对伪哈希 | `Account.canLogin` + `LoginUseCase` | FR-005 |
| 不能停用操作人自己所属的用户；不能停用最后一个 `ACTIVE` 用户（并发下也成立） | `SelfProtectionRule` + `pg_advisory_xact_lock` 串行化（`UserStatusLock`），锁内读 `countActive()` | FR-025 |
| 不能强制下线操作人自己正在使用的会话 | `SelfProtectionRule.checkTerminate`（先于任何终止动作） | FR-030 |
| 停用用户后其全部会话立即结束，且不会留下已停用用户的有效会话（含并发登录） | 停用用例（**提交后**再终止其全部账号会话）+ 登录用例（写入会话后**复查**用户状态，已停用则终止刚建会话并拒绝） | FR-016、FR-024 |
| 新建用户：用户 + 账号 + 凭据同事务；同键重放返回原结果；并发抢同一账号名只有一个成功 | `CreateUserUseCase` + `IdempotencyStore` + 唯一索引 | FR-021 ~ FR-023 |
| 改密只能改**操作人自己的账号**（路径账号 ≠ 操作人账号 → 403 `client_forbidden`）；需要当前密码；当前密码连续错误按来源限制 | `ChangePasswordUseCase` + `PasswordChangeThrottle` | FR-026、FR-027 |
| 机密会话标识只存哈希、对外只用会话引用；密码、哈希、服务密钥不进日志 | `RedisSessionStore` + `LogRedaction` | FR-031、FR-042 |
| 写操作必须带操作人，缺失即 400 `operator_required`；每次写操作（含被拒绝）留一条日志 | `OperatorContextResolver` + `WriteOperationLogger` | FR-008、FR-034 |

### 3.3 策略参数（默认值，均可配置，见 `apps/uam/AGENTS.md`）

| 策略 | 规则 |
|---|---|
| 会话时限 | 空闲 **30 分钟**（`uam.session.idle`）、绝对 **12 小时**（`uam.session.absolute`）；有效到期取二者较小者；`verify` 距上次写入超过 **60 秒**（`uam.session.touch-interval`）才续期，因此有效空闲时间在 29–30 分钟之间。结束是终态，无「恢复」。仅管理员强制下线会另留一个 **10 分钟**的墓碑（`uam.session.terminated-notice-ttl`，键 `uam:term:{sha}`），供 `verify` 回 `reason: terminated`。 |
| 登录失败限制 | 键 `uam:throttle:login:{sha256(规范化账号名\|来源地址)}`；**限流闸门在哈希比对之前**由 `throttle-admit.lua` 原子占用一次名额（`INCR`），并发的错误尝试不会同时通过闸门；前 **5 次**放行，第 6 次起拒绝该来源（429 + `Retry-After`，期间凭据正确也拒绝），计数达到上限时把 TTL 重置为 **15 分钟**封锁时长，超限后不再刷新；成功登录即清零；**不锁账号**，其他来源不受影响。 |
| 改密限流 | 键 `uam:throttle:pwchg:{sha256(accountId\|来源地址)}`，口径同登录（同一个 `throttle-admit.lua`，先占名额再比对）；当前密码一经验证即清零（新密码的问题不算猜测）。 |
| 账号名 | 3–32 位字母、数字或 `_` `.` `-`，大小写不敏感。 |
| 密码 | 至少 10 位且同时含字母与数字（`uam.password.min-length`），**上限 128 个码点**（登录 / 改密请求的 `password` 按 UTF-16 字符数限制为 256，128 个码点的任何密码都一定能提交）；改密须不同于当前。 |
| 用户名 | 去首尾空白后 1–64 字符，不要求唯一。 |
| 幂等 | `Idempotency-Key` 10 分钟内同键重放返回原 201；进行中标记 30 秒；**只缓存 201**（被拒绝或失败会释放占位，同键修正后可重试）；同键但请求不同（用户名 / 账号名）→ 422；同键请求仍在处理且等待超时（默认 5 秒）→ 409 `idempotency_in_progress`。 |

### 3.4 状态机

- **User**：`ACTIVE → DISABLED`（停用，校验自我保护，提交后终止其会话）；`DISABLED → ACTIVE`（启用，**不恢复**已结束会话）；重复操作幂等。图见 [domain-state-user.svg](../system-design/diagrams/domain-state-user.svg)。
- **Session**：登录成功 → 有效；退出 / 强制下线 / 用户停用 / 被取代 / 空闲到期 / 绝对到期 → 结束（终态）；其中**只有管理员强制下线**会在终止的同时留下 10 分钟的墓碑 `uam:term:{sha}`，其余结束方式不留。图见 [domain-state-session.svg](../system-design/diagrams/domain-state-session.svg)。
- 类图见 [domain-class.svg](../system-design/diagrams/domain-class.svg)。

## 4. UAM 接口与权限模型

### 4.1 接口（`contracts/uam/openapi.yaml`）

| 接口 | 用途 | 需要 |
|---|---|---|
| `POST /v1/auth/login` | 账号名 / 密码登录，返回会话标识与会话数据；请求体含 `clientIp` | 服务密钥 + 断言权限 |
| `POST /v1/auth/sessions/verify` | 校验会话标识，返回会话数据（只读 Redis） | 同上 |
| `POST /v1/auth/logout` | 终止该会话标识对应的会话；幂等（未知标识也 204） | 同上 |
| `GET /v1/users`、`GET /v1/users/{userId}` | 用户分页（`query` 搜索，默认每页 20、上限 100）、详情含全部账号 | 有效服务密钥即可 |
| `GET /v1/online-users`、`GET /v1/users/{userId}/sessions` | 在线用户分页（按最近活动倒序）、某用户的有效会话（带 `current` 标注） | 同上（可选带 `X-Operator-*`） |
| `POST /v1/users` | 新建用户 + 账号（`Idempotency-Key`） | 服务密钥 + 断言权限 + 操作人头 |
| `POST /v1/users/{userId}/disable`、`/enable` | 停用 / 启用 | 同上 |
| `DELETE /v1/sessions/{sessionRef}` | 强制下线 | 同上 |
| `POST /v1/accounts/{accountId}/password` | 修改操作人自己账号的密码 | 同上 |
| `/actuator/health/**` | 存活 / 就绪探针（只暴露 UP / DOWN），**免校验** | 无 |
| `/actuator/prometheus` | 指标 | 有效服务密钥 |

### 4.2 客户端权限决策（`ClientPermissions`，唯一决策点）

按 **HTTP 方法**而非路径判定：`GET` / `HEAD` 只需**有效服务密钥**；其余方法（登录、校验、退出、改密与全部写操作）需要 `assert-end-user-context`。新增写接口默认受限（失败即关闭）；契约测试 `ClientAuthContractIT` 遍历契约的每个接口固定这一决定。

| 情形 | 结果 |
|---|---|
| 缺失、格式不对、无效、已撤销的密钥 | 401 `client_unauthenticated`（响应不透露哪里不对，不回显密钥）；写拒绝日志 `uam.client.rejected` |
| 密钥有效但缺权限 | 403 `client_forbidden` |
| 有权限的客户端调写接口但缺 / 非法 `X-Operator-*`（`/v1/auth/**` 除外） | 400 `operator_required` |
| 请求无法解析或类型不符（坏 JSON、非 UUID 路径 id、`page=abc` 等） | 400 `bad_request` |
| 请求体 / 查询 / `Idempotency-Key` 不符合规则 | 422 `validation_failed`（带 `errors[]`） |
| 操作人 `X-Operator-Session-Ref` == 强制下线目标 | 409 `cannot_terminate_own_session` |
| 操作人用户 == 停用目标 | 409 `cannot_disable_self` |
| 停用最后一个 `ACTIVE` 用户 | 409 `cannot_disable_last_active_user` |
| 改密时路径账号 ≠ 操作人账号 | 403 `client_forbidden` |

### 4.3 错误码

`invalid_credentials`、`session_invalid`、`client_unauthenticated`、`client_forbidden`、`operator_required`、`bad_request`、`throttled`（带 `Retry-After`）、`account_name_taken`、`idempotency_in_progress`、`validation_failed`（一律 422，带 `errors[{field, reason}]`）、`cannot_disable_self`、`cannot_disable_last_active_user`、`cannot_terminate_own_session`、`session_not_found`、`user_not_found`、`current_password_incorrect`、`weak_password`、`same_as_current`、`internal_error`；错误体为 RFC 9457 `application/problem+json`。**一个错误码对应一个状态码**：`validation_failed` 一律 **422**（请求体字段缺失 / 为空 / 过长、`Idempotency-Key`、分页与查询规则 `page=0` / `size>100`、领域规则校验）；请求无法解析或类型不符（JSON 格式错误或为空、JSON 类型不对、路径变量或查询参数类型不符，如非 UUID 的 `userId`、`page=abc`）一律 **400** `bad_request`（不回显请求值、不带 `errors`）；`operator_required` 仍为 400；已知但不存在的 id 仍为 404。`Problem` 另有**可选**字段 `reason`（枚举 `terminated`），只出现在 `verify` 的 401 `session_invalid` 上，且仅当该会话在 10 分钟内被管理员强制下线，其余情形不带。Admin Center 的错误码 → 界面文案映射见 `web-routes.md` §5。

## 5. 核心业务流程

### 5.1 UAM 侧

| 流程 | 现状（按代码） |
|---|---|
| **登录** | Admin Center 同源校验、必填校验（为空不调用 UAM）、按 `TRUSTED_PROXY_HOPS` 取终端来源地址（取不到则拒绝）→ UAM：原子占用登录限流名额（超限 429）→ 读账号 / 用户 / 凭据 → **总是先做一次哈希比对**（不存在则比对伪哈希）→ 再判账号与用户状态，任一不满足统一 `invalid_credentials` → `login.lua` 写新会话并顶掉旧会话 → **复查用户状态**（已停用则终止刚建会话并拒绝）→ 清零该来源的计数 → 返回会话标识与会话数据。（哈希需要升级时用**条件更新** `upgradePasswordHash`：库里的哈希仍是比对时读到的那一个才替换，避免旧密码的升级结果覆盖刚改的新密码。）图：[seq-login.svg](../system-design/diagrams/seq-login.svg)。 |
| **校验（每个浏览器请求）** | Admin Center 对每个有 cookie 的请求调 `verify`；UAM 只读 `uam:sess:{sha256(id)}`（HASH 内已快照用户名、账号名、种类、到期，**不访问 PostgreSQL**）；无效 / 过期 / 被取代 / 被终止 / 过长或空白 → 401 `session_invalid`（会话不存在时再按所呈现标识的哈希查 `uam:term:{sha}`，命中即被管理员强制下线 → body 带 `reason: terminated`，否则不带）；距上次写入 > 60 秒才 `touch.lua` 续期。会话一结束，下一次请求即判未登录（窗口为 0）。图：[seq-verify-and-call.svg](../system-design/diagrams/seq-verify-and-call.svg)。 |
| **退出 / 到期** | `logout`：按会话标识找到会话引用后 `terminate.lua`（含 `WAIT 1`），幂等，不写 `uam.admin.write`。到期：Redis TTL 删除会话键；`uam:online` 的过期条目由列表脚本的 `ZREMRANGEBYSCORE` 清理并以到期分数 / `expiresAt` 保证不被计入。 |
| **停用用户** | 事务内取 `pg_advisory_xact_lock` → 读目标 → `SelfProtectionRule.checkDisable`（自己 / 最后一个）→ 置 `DISABLED`；**提交后**对其全部账号 `terminate-all.lua`（含 `WAIT 1`）。对已停用用户重复停用同样成功并再次确保会话已终止。账号自身状态不变。 |
| **启用用户** | 事务内取同一把锁并置 `ACTIVE`；**不恢复**会话；幂等。 |
| **强制下线** | `SelfProtectionRule.checkTerminate` → `terminate.lua`（按 `uam:sref:{ref}`，`acct` 指针仅在仍指向该会话时才删；同一脚本里原子写墓碑 `uam:term:{sha}`，TTL `uam.session.terminated-notice-ttl` 默认 10 分钟）→ `WAIT 1`；目标不存在 → 404 `session_not_found`。只终止该会话，不改账号与用户状态；被下线者之后可再次登录。图：[seq-terminate-session.svg](../system-design/diagrams/seq-terminate-session.svg)。 |
| **新建用户** | 校验 `Idempotency-Key` → 抢占 `uam:idem:{sha256(clientId\|key)}`（`SET NX`）→ `AccountProvisioningService` 单事务创建用户 + 账号 + 凭据（账号名冲突 409 `account_name_taken`，规则校验失败 422 `validation_failed`，请求结构不合法 400）→ 成功缓存响应快照（不含密码 / 哈希）→ 写日志；被拒绝 / 失败释放占位。 |
| **修改密码** | 路径账号须等于操作人账号 → 原子占用改密限流名额（超限 429）→ **总是做一次哈希比对**（账号不存在比对伪哈希）→ 当前密码错 `current_password_incorrect`（占用的名额即计为一次失败）→ 当前密码正确则清零 → 新密码强度（`weak_password`）与不同于当前（`same_as_current`）→ 只更新该账号的哈希。不触碰会话，不轮换会话标识（账号与会话一对一）。 |
| **服务密钥轮换** | 见 §5.3。 |

### 5.2 Admin Center 侧（会话三态）

| 状态 | 判定 | 表现 |
|---|---|---|
| `authenticated` | `verify` 200 | 正常渲染；顶栏显示用户名 |
| `anonymous` | 无 cookie，或 UAM 401 `session_invalid` | 受保护页面 → `/login?next=<原路径>`；带过 cookie 则经 `GET /session/expire`（重新 `verify` 仍无效才清 cookie）并提示「会话已结束，请重新登录。」（`/login?reason=session_ended`）；若 401 带 `reason: terminated` 则 `/login?reason=session_terminated`，提示「你的会话已被管理员强制下线」；`next` 只接受以单个 `/` 开头的站内路径 |
| `unverified` | 超时（默认 1.5 秒）、5xx、连接失败，或 UAM 拒绝 Admin Center 本身（401 `client_unauthenticated` / 403 `client_forbidden`，即服务密钥 / 权限配置错误） | 受保护页面显示「服务暂不可用」，**保留 cookie、不登出**；写操作提示「操作未生效」，绝不显示成功；后两种拒绝另写告警 `admin-center.uam.misconfigured`；修好后无需重新登录 |

写入口（登录、新建、停用、启用、强制下线、改密、`POST /logout`）第一步都是同源校验（`Origin` / `Sec-Fetch-Site`，失败 403）。退出：UAM 不可用或拒绝时**仍清 cookie 并回登录页**（此时 UAM 侧会话要等到期才失效，写一条不含会话标识的 warn 日志）。

### 5.3 服务密钥轮换（FR-044）

1. `generate-service-key --client admin-center` 生成新密钥（标准输出只出现一次，哈希与 `UAM_CLIENTS` 片段写标准错误）；该命令在 Spring 启动**之前**执行，不连库、不连 Redis、不需要环境变量。
2. 把**新哈希加为第二个** `keyHashes`，滚动发布 UAM（此时同一客户端 2 个有效哈希；超过 2 个或重复则 UAM 启动失败）。
3. Admin Center 换新 `UAM_SERVICE_KEY`，滚动发布。
4. 从 `UAM_CLIENTS` 删旧哈希，再滚动发布 UAM；之后旧密钥 → 401。

本地演练：`infra/local/scripts/rotate-key.sh rotate`。

### 5.4 在线用户与会话查询

`GET /v1/online-users` 由 `online.lua` 清理到期条目并**扫描整个 `uam:online`**，UAM 内按用户聚合（会话数、最近活动），分页后回 PostgreSQL 批量取用户名。`GET /v1/users/{id}/sessions` 取该用户全部账号，逐个读 `uam:acct:{accountId}:sess`（一对一，账号数极少）。已知的规模上限见 §8。

## 6. 需求对照表

「实现位置」中 UAM = `apps/uam/src/main/java/com/s2u2m/uam/…`，AC = `apps/admin-center/src/…`。「验证」中的测试类 / 规格均在对应应用的 `src/test`（UAM）/ `tests`（AC）下；各用户故事的 E2E 为 `tests/e2e/usN-*.spec.ts`。**最终回归结果见 §7**（本表只说明验证手段，不代表某次运行的结果）。

### 6.1 功能需求 FR-001 ~ FR-044

| FR | 实现位置 | 验证 |
|---|---|---|
| FR-001 登录页 | AC `app/(auth)/login/{page,actions}.tsx`、`components/auth/login-form.tsx` | `login-actions.test.ts`、`login-form.test.tsx`、`us1-login.spec.ts` |
| FR-002 多账号识别为同一用户 | UAM `application/LoginUseCase`、`domain/model/Session` | `LoginUseCaseTest`、`us1-login.spec.ts`（S1） |
| FR-003 cookie 属性 | AC `lib/session/cookie.ts` | `cookie.test.ts`、`login-actions.test.ts`、`us1-login.spec.ts` |
| FR-004 账号与会话一对一 | UAM `resources/redis/login.lua`、`RedisSessionStore` | `RedisSessionStoreIT`、`ConcurrentLoginIT`、`us1-login.spec.ts`（S3） |
| FR-005 统一失败提示、先哈希再判状态 | UAM `LoginUseCase`、`Argon2PasswordHasher`（伪哈希） | `LoginUseCaseTest`、`LoginTimingIT`、`AuthContractIT`、`us1-login.spec.ts` |
| FR-006 失败限制 | UAM `RedisGuards`、`throttle-admit.lua`、`LoginThrottlePolicy`、`LoginUseCase` | `LoginThrottleIT`、`ConcurrentThrottleIT`、`LoginThrottlePolicyTest`、`AuthContractIT`、`us1-login.spec.ts` |
| FR-007 `verify` 返回会话数据 | UAM `VerifySessionUseCase`、`AuthController` | `VerifySessionUseCaseTest`、`AuthContractIT` |
| FR-008 UAM 不基于用户会话、操作人以请求头传入 | UAM `ClientAuthFilter`、`OperatorContextResolver`；AC `lib/uam/client.ts` | `ClientAuthFilterTest`、`OperatorContextResolverTest`、`ClientAuthContractIT`、AC `client.test.ts` |
| FR-009 会话无效 / UAM 不可用的处理 | AC `lib/session/get-session.ts`、`app/session/expire/route.ts` | `get-session.test.ts`、`session-expire.test.ts`、`misconfig.test.ts`、`us11-prototype-states.spec.ts`（UAM 暂不可用）、`us12-rotation.spec.ts` |
| FR-010 退出与自动失效 | UAM `LogoutUseCase`、`SessionPolicy`；AC `app/logout/route.ts` | `LogoutUseCaseTest`、`SessionExpiryIT`、`AuthLogoutContractIT`、`logout-route.test.ts`、`us8-logout-expiry.spec.ts` |
| FR-011 会话结束后任一实例视为未登录 | UAM `RedisSessionStore`（terminate 脚本）；AC 每请求 `verify` | `TerminateSessionIT`、`DisableUserIT`、`TwoInstanceConsistencyIT`、`us8-logout-expiry.spec.ts`、`scale-checks.sh` |
| FR-012 登录后回原页（站内路径） | AC `lib/session/next-path.ts`、`(shell)/layout.tsx` | `next-path.test.ts`、`login-actions.test.ts`、`us1-login.spec.ts` |
| FR-013 UAM 是权威，Admin Center 不持久化 | UAM `domain` 无框架依赖；AC 无数据库依赖 | `ArchitectureTest`、`security-check.sh`（S2：`package.json` 无数据库驱动 / ORM） |
| FR-014 查询能力 | UAM `UsersController`、`SessionsController` 与 `List*` / `Get*` 用例 | `ListUsersIT`、`GetUserIT`、`OnlineUsersIT`、`UserSessionsIT`、`UsersReadContractIT`、`SessionsReadContractIT` |
| FR-015 管理能力与并发规则在 UAM | UAM 各写用例 | `CreateUserIT`、`DisableUserIT`、`TerminateSessionIT`、`ChangePasswordIT` 及对应 `*ContractIT` |
| FR-016 停用使会话失效 | UAM `DisableUserUseCase`、`terminate-all.lua` | `DisableUserIT`、`DisableVsLoginRaceIT` |
| FR-017 ~ FR-018 用户列表、搜索、分页 | UAM `ListUsersUseCase`、`JdbcUserRepository`（`pg_trgm`）；AC `users/page.tsx`、`user-list.tsx`、`user-search-box.tsx` | `ListUsersIT`（含 `EXPLAIN` 走 GIN）、`user-list.test.tsx`、`user-search-box.test.tsx`、`us2-users.spec.ts` |
| FR-019 ~ FR-020 用户详情、空态、不存在 | UAM `GetUserUseCase`；AC `users/[userId]/page.tsx`、`user-detail.tsx`、`account-list.tsx` | `GetUserIT`、`user-detail.test.tsx`、`account-list.test.tsx`、`us2-users.spec.ts` |
| FR-021 ~ FR-023 新建用户、校验、凭据保护 | UAM `CreateUserUseCase`、`AccountProvisioningService`、`AccountNameRules`、`PasswordPolicy`；AC `users/new/*`、`user-form.tsx` | `CreateUserUseCaseTest`、`AccountProvisioningServiceTest`、`CreateUserIT`、`ConcurrentCreateUserIT`、`IdempotencyStoreIT`、`UsersWriteContractIT`、`new-user-actions.test.ts`、`user-form.test.tsx`、`us4-create-user.spec.ts` |
| FR-024 停用 / 启用（确认、幂等） | UAM `DisableUserUseCase`、`EnableUserUseCase`；AC `users/[userId]/actions.ts`、`status-actions.tsx` | `DisableEnableUserUseCaseTest`、`DisableUserIT`、`UserStatusContractIT`、`status-actions.test.tsx`、`user-status-actions.test.ts`、`us5-disable.spec.ts` |
| FR-025 自我保护与最后一个正常用户 | UAM `SelfProtectionRule`、`JdbcUserRepository.lockStatusChanges` | `SelfProtectionRuleTest`、`ConcurrentDisableIT`、`UserStatusContractIT`；界面入口见 §8；UAM 的「最后一个正常用户」规则是服务端兜底：能发起停用的运维自己必定是正常用户，所以只会在两个运维同时互相停用的竞态或其他调用方时触发 |
| FR-026 ~ FR-027 修改自己的密码 | UAM `ChangePasswordUseCase`、`AccountsController`、`PasswordChangeThrottle`；AC `account/password/*`、`change-password-form.tsx` | `ChangePasswordUseCaseTest`、`ChangePasswordIT`、`AccountPasswordContractIT`、`password-actions.test.ts`、`change-password-form.test.tsx`、`us7-password.spec.ts` |
| FR-028 ~ FR-029 在线用户与会话详情、当前会话标注 | UAM `ListOnlineUsersUseCase`、`ListUserSessionsUseCase`、`online.lua`；AC `sessions/*`、`online-user-list.tsx`、`session-list.tsx` | `OnlineUsersIT`、`UserSessionsIT`、`SessionsReadContractIT`、`online-user-list.test.tsx`、`session-list.test.tsx`、`us3-sessions.spec.ts` |
| FR-030 强制下线 | UAM `TerminateSessionUseCase`、`terminate.lua`；AC `sessions/[userId]/actions.ts`、`terminate-dialog.tsx` | `TerminateSessionUseCaseTest`、`TerminateSessionIT`、`SessionTerminateContractIT`、`terminate-dialog.test.tsx`、`terminate-actions.test.ts`、`us6-terminate.spec.ts`；被强制下线的浏览器 10 分钟内在登录页看到专属提示，其余结束方式仍为通用提示（T194–T196） |
| FR-031 不泄露会话标识 / 密码 / 哈希 | UAM `LogRedaction`、`RedisSessionStore`（只存 SHA-256）；DTO 只含会话引用 | `LogRedactionTest`、`UserSessionsIT`、`ChangePasswordIT`、`security-check.sh`（runtime R9） |
| FR-032 列表反映最新状态 | UAM `online.lua`（清理到期条目） | `OnlineUsersIT`、`SessionExpiryIT`、`stale-session.test.tsx`、`us8-logout-expiry.spec.ts` |
| FR-033 写操作防跨站伪造、明确反馈 | AC `lib/security/origin-check.ts`（所有写入口第一步） | `origin-check.test.ts`、各 actions 单元测试、`us8-logout-expiry.spec.ts`（跨站 POST 被拒）、`security-check.sh`（S6、runtime R1 ~ R8） |
| FR-034 写操作日志 | UAM `WriteOperationLogger`、各写用例 | `WriteOperationLoggerTest`、各用例的 `*UseCaseTest`、`LogRedactionTest` |
| FR-035 自适应 | AC `components/shell/*`、`globals.css` | `us9-responsive.spec.ts`（六个视口）、`app-shell.test.tsx` |
| FR-036 触控目标、键盘、焦点 | AC `components/**`（shadcn/Radix） | `us9-a11y.spec.ts`（axe、键盘、焦点）、`us9-touch-and-content.spec.ts` |
| FR-037 ~ FR-040 横向扩展、一致、重启不登出 | 两服务无实例本地状态；UAM `server.shutdown=graceful`；`infra/local/` 多实例编排 | `TwoInstanceConsistencyIT`、`GracefulShutdownIT`、`scale-checks.sh`、`rolling-restart.sh`；结果见 `infra/load/RESULTS.md` |
| FR-041 原型 | `specs/002-admin-center-uam/prototype/` | `us11-prototype-states.spec.ts`、`us11-no-out-of-scope.spec.ts`（实现与原型一致性） |
| FR-042 每个接口校验服务密钥 | UAM `ClientAuthFilter`、`ClientRegistry`（SHA-256 常量时间比较）、`ClientRejectionLogger` | `ClientAuthFilterTest`、`ClientRegistryTest`、`ClientAuthContractIT`（遍历契约每个接口）、`RejectedCallLogIT`、`ClientRejectionLoggerTest` |
| FR-043 客户端权限 | UAM `ClientPermissions`、`ClientIdentity` | `ClientPermissionsTest`、`ClientAuthContractIT` |
| FR-044 不停机轮换、工具、启动失败、`unverified` 处理 | UAM `ClientRegistry`（1–2 哈希）、`GenerateServiceKeyCommand`；AC `lib/uam/errors.ts`、`lib/logging.ts`、`instrumentation.ts` | `ClientRegistryRotationTest`、`GenerateServiceKeyCommandTest`、`StandaloneCommandsTest`、`misconfig.test.ts`、`instrumentation.test.ts`、`us12-rotation.spec.ts`、`rotate-key.sh` |

### 6.2 成功标准 SC-001 ~ SC-016

| SC | 内容（摘要） | 实现 / 验证 | 状态 |
|---|---|---|---|
| SC-001 | 登录到看到用户列表 95% ≤ 30 秒 | `us1-login.spec.ts` 记录单次耗时（注解 `SC-001`）；**自动化只测单次耗时，不是 95 分位的用户研究** | 仅自动化耗时 |
| SC-002 | ≤ 2 次点击到用户详情 / 在线用户会话详情 | `us2-users.spec.ts`、`us3-sessions.spec.ts` | 自动化 |
| SC-003 | 详情账号数 / 在线集合 100% 一致 | `GetUserIT`、`OnlineUsersIT`、`us2-users.spec.ts`、`us3-sessions.spec.ts` | 自动化 |
| SC-004 | 新建 90% ≤ 2 分钟；冲突 / 失败 100% 指出原因 | `us4-create-user.spec.ts` 记录创建耗时；原因提示由 `user-form.test.tsx` 与 E2E 断言；**「90% 的人」未做用户测试** | 仅自动化耗时 |
| SC-005 | 停用后会话 / 登录 / 在线列表 100% 一致 | `DisableUserIT`、`us5-disable.spec.ts` | 自动化 |
| SC-006 | 强制下线 100% 生效、≤ 4 次点击 | `TerminateSessionIT`、`us6-terminate.spec.ts` | 自动化 |
| SC-007 | 改密 90% ≤ 1 分钟；旧 / 新密码 100% | `ChangePasswordIT`、`us7-password.spec.ts` 记录耗时；**「90% 的人」未做用户测试** | 仅自动化耗时 |
| SC-008 | 六个视口全流程无整页横向滚动 | `us9-responsive.spec.ts`（320 / 375 / 768 / 1024 / 1440 / 2560） | 自动化（仅 Chromium） |
| SC-009 | 10 万用户 / 1 万会话下列表与详情 95% ≤ 2 秒 | `infra/load/k6/list-and-detail.js`、`seed-data.sh`；**仅在 `RESULTS.md` §4 记录的负载下测得**（最高 100 次 / 秒，在线用户列表 20 次 / 秒，单次运行，非独占机器，不含浏览器渲染）；超过约 45 次 / 秒的在线用户列表见 §8 | **仅在文档化的负载下测得** |
| SC-010 | 多实例 100% 识别、新用户立即可见 | `TwoInstanceConsistencyIT`、`scale-checks.sh`（`RESULTS.md` §2） | 自动化 + 脚本 |
| SC-011 | 1 → 4 实例吞吐 ≥ 3.2 倍 | `infra/load/k6/validate-throughput.js`、`infra/load/SC-011.md` | **未测量**（用户决定：部署到独立机器后再测；仅脚本冒烟，不是判定） |
| SC-012 | 滚动重启 0 次意外登出 | `rolling-restart.sh`（`RESULTS.md` §3）、`GracefulShutdownIT` | 脚本（本地 3+3 编排） |
| SC-013 | 会话结束后 100% 视为未登录 | `us8-logout-expiry.spec.ts`、`TwoInstanceConsistencyIT`、`scale-checks.sh` | 自动化 + 脚本 |
| SC-014 | 无明文密码 / 哈希 / 会话标识 / 会话数据泄露；伪造写请求 100% 被拒；写操作 100% 留日志 | `security-check.sh`（static + runtime）、`LogRedactionTest`、`WriteOperationLoggerTest`、`origin-check.test.ts` | 自动化 + 脚本 |
| SC-015 | 原型覆盖 100% 页面与状态，经确认后实现 | `us11-prototype-states.spec.ts`、`us11-no-out-of-scope.spec.ts`；原型 README 标注已确认（2026-10-23） | 自动化 + 用户确认 |
| SC-016 | 密钥相关拒绝 100%；轮换 0 登出 / 0 失败；密钥不出现于页面 / 响应 / 日志 | `ClientAuthContractIT`、`RejectedCallLogIT`、`us12-rotation.spec.ts`、`rotate-key.sh`、`security-check.sh` | 自动化 + 脚本（轮换演练见 §7、§8） |

## 7. 验证记录

按 [quickstart.md](../../specs/002-admin-center-uam/quickstart.md) 的场景 S1 ~ S19 列出**自动化证据**（测试类 / Playwright 规格 / 脚本）。「最终回归结果」列由终验时填写，其余列只陈述证据**是什么**，不代表某次运行的结果。已记录的专项结果（多实例、滚动重启、规模）见 [infra/load/RESULTS.md](../../infra/load/RESULTS.md)。

**终验（2026-10-24，全新栈）**：UAM `mvn verify` 389 单元 + 355 集成测试通过，行覆盖率 97.66%；Admin Center 681 个单元测试通过，行覆盖率 99.49%，lint / typecheck / build 通过；
E2E 全部规格在 w1440 / w375 / w768 上 349 通过 / 8 按设计跳过 / 0 失败，在 w320 / w1024 / w2560 上 346 通过 / 10 按设计跳过 / 1 失败——失败的是 us1 中「不存在的账号名」用例：它在每个视口项目里都用同一个账号名与来源地址，永远登录不成功、计数不清零，第 6 个项目被限流拦下，是测试隔离问题（改为每次运行用独立账号名后 w2560 上 us1 全部 13 个通过），不是产品缺陷；
`security-check.sh all` 137 项通过 / 0 失败；`scale-checks.sh` 31 / 31；`rolling-restart.sh` 8333 个请求 0 失败。未跑的：SC-011 吞吐（见 §8）、深色模式 / 真机 / 读屏器 / 非 Chromium。

| # | 场景 | 自动化证据 | 最终回归结果 |
|---|---|---|---|
| S1 | 多账号登录识别为同一用户 | UAM `LoginUseCaseTest`、`AuthContractIT`；AC `us1-login.spec.ts`、`login-actions.test.ts`、`cookie.test.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过 |
| S2 | 登录失败 / 限流 / 停用 | UAM `LoginUseCaseTest`、`LoginTimingIT`、`LoginThrottleIT`、`AuthContractIT`；AC `us1-login.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过 |
| S3 | 单会话（一对一） | UAM `RedisSessionStoreIT`、`ConcurrentLoginIT`；AC `us1-login.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过 |
| S4 | 用户列表、搜索、详情 | UAM `ListUsersIT`、`GetUserIT`、`UsersReadContractIT`；AC `us2-users.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过 |
| S5 | 新建用户 | UAM `CreateUserIT`、`ConcurrentCreateUserIT`、`IdempotencyStoreIT`、`UsersWriteContractIT`；AC `us4-create-user.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过 |
| S6 | 停用 / 启用 | UAM `DisableUserIT`、`ConcurrentDisableIT`、`DisableVsLoginRaceIT`、`UserStatusContractIT`；AC `us5-disable.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过 |
| S7 | 在线用户与会话 | UAM `OnlineUsersIT`、`UserSessionsIT`、`SessionsReadContractIT`；AC `us3-sessions.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过 |
| S8 | 强制下线 | UAM `TerminateSessionIT`、`SessionTerminateContractIT`；AC `us6-terminate.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过；强制下线后登录页显示「你的会话已被管理员强制下线」 |
| S9 | 修改密码 | UAM `ChangePasswordIT`、`AccountPasswordContractIT`；AC `us7-password.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过 |
| S10 | 退出与到期 | UAM `LogoutUseCaseTest`、`SessionExpiryIT`（时限调到秒级；E2E 栈时限为 30 分钟 / 12 小时，到期只由该 IT 覆盖）、`AuthLogoutContractIT`；AC `us8-logout-expiry.spec.ts` | 通过（E2E 六个视口全量）；UAM `mvn verify` 通过；退出、被取代、被强制下线、被停用后旧 cookie 重放 100% 视为未登录；到期只由 `SessionExpiryIT` 覆盖 |
| S11 | UAM 不可用 | AC `us11-prototype-states.spec.ts`（停掉 UAM 后的读 / 写 / 恢复）、`get-session.test.ts`、`client.test.ts` | 通过（E2E 六个视口：停掉 UAM 后读显示「服务暂不可用」、写显示「操作未生效」、恢复后无需重新登录） |
| S12 | 响应式、键盘、axe | AC `us9-responsive.spec.ts`、`us9-a11y.spec.ts`、`us9-touch-and-content.spec.ts`（六个视口 project） | 通过（六个视口：三个 US9 规格，axe 无严重违规、触控 ≥ 44px、无整页横向滚动） |
| S13 | 无会话粘滞与多实例一致 | UAM `TwoInstanceConsistencyIT`；`infra/local/scripts/scale-checks.sh`（3+3 栈；断言内容与结果见 `infra/load/RESULTS.md` §2） | 通过：`scale-checks.sh` 31 项全过；30 次轮询在三个 Admin Center 实例上各 10 次 |
| S14 | 滚动重启 | UAM `GracefulShutdownIT`；`infra/local/scripts/rolling-restart.sh` | 通过：`rolling-restart.sh` 持续访问 8333 个请求，0 次意外登出、0 个失败请求，重启后会话 15 / 15 有效 |
| S15 | 规模与吞吐 | `infra/load/k6/{seed-data.sh,list-and-detail.js,validate-throughput.js}`；SC-009 见 `RESULTS.md` §4，**SC-011 未测量** | SC-009 通过（10 万用户 / 1 万会话，p95 约 93 ms，见 `RESULTS.md` §4）；**SC-011 未测量**，用户决定部署到独立机器后再测 |
| S16 | UAM 直连：服务密钥、权限、操作人 | UAM `ClientAuthContractIT`、`ClientAuthFilterTest`、`ClientPermissionsTest`、`ClientRegistryTest`、`OperatorContextResolverTest`、`TerminateSessionUseCaseTest`、`DisableEnableUserUseCaseTest`、`RejectedCallLogIT`；「非 Admin Center 主机连不上 UAM」由编排拓扑保证（UAM 不映射宿主机端口），无自动化探测 | 通过（UAM `ClientAuthContractIT` 遍历契约全部 12 个操作；`security-check.sh` 的写入口同源检查） |
| S17 | 安全与日志抽查 | `infra/local/scripts/security-check.sh`（static / runtime）；UAM `LogRedactionTest`、`WriteOperationLoggerTest`；AC `origin-check.test.ts` | 通过：`security-check.sh all` 137 项通过 / 0 失败（含 R10 RSC 局部渲染不能绕过登录） |
| S18 | 评审界面设计原型 | AC `us11-prototype-states.spec.ts`、`us11-no-out-of-scope.spec.ts`；`prototype/README.md`（已确认 2026-10-23） | 通过（`us11-*` 两个规格，六个视口） |
| S19 | 服务密钥轮换与配置错误 | AC `us12-rotation.spec.ts`；`infra/local/scripts/rotate-key.sh`；UAM `ClientRegistryRotationTest`、`GenerateServiceKeyCommandTest`、`ClientAuthContractIT` | 通过：E2E 轮换与密钥配错（w1440）；多实例栈持续访问下滚动轮换 341 / 341 次页面请求正常、0 登出、0 密钥告警，删旧哈希后旧密钥在三个 UAM 上均为 401 |

**质量门禁**（各应用 `AGENTS.md` 为准）：UAM `spotless:check`、`compile`、`verify`（行覆盖率 ≥ 90%，JaCoCo）；Admin Center `lint`、`typecheck`、`test:cov`（行覆盖率 ≥ 90%，Jest）、`build`。本文不复制覆盖率数字。

## 8. 已知限制与待决事项

| # | 项目 | 说明 | 状态 |
|---|---|---|---|
| 1 | **T156：用户菜单非模态** | 为消除 axe 的 `aria-hidden-focus`（serious），顶栏用户菜单改为 `modal={false}`；唯一的行为差异：点击菜单外区域时既关闭菜单、也会点到下面的元素。 | **已确认保持非模态（用户决定）** |
| 2 | **SC-011 未测量** | 1 → 4 实例吞吐 ≥ 3.2 倍需要独占机器与分机压测；本机只跑过脚本冒烟（不是判定）。方法见 `infra/load/SC-011.md`。 | **未测量，未判定；用户决定部署到独立机器后再测** |
| 3 | **SC-009 仅在文档化的负载下测得** | 见 §6.2；单次运行、非独占机器、不含浏览器渲染。 | 已记录口径 |
| 4 | **在线用户列表的规模上限** | `online.lua` 在一次 Redis 调用里扫描整个 `uam:online`（每成员 1 次 `GET` + 1 次 `HMGET`）：1 万会话时约 22 ms / 次，Redis 单线程，理论上限约 45 次 / 秒（实测 45 次 / 秒时 14.3% 页面触发 1.5 秒超时，并拖累所有实例的会话校验）；上限与 UAM 实例数无关，且随会话数线性变差。**提议的最小修复**（未实施）：只读 `uam:online` 的分数，由分数推出最近活动时间。 | 待用户确认后实施 |
| 5 | **单实例做密钥轮换会有几秒「服务暂不可用」** | 单实例栈重建 UAM 的几秒内 Admin Center 不可达（演练中 16 / 72 次页面请求），不是密钥失败、用户不被登出；多实例栈上滚动轮换 341 次页面请求全部正常（`tasks.md` T184）。 | 已知，预期行为 |
| 6 | **旧分支 git 历史里有私钥占位** | `git log --all` 中有三个来自旧演示项目的 PEM 私钥头标记 占位（`jwt-rsa-key.pem`，正文只有 24 个字符，不是有效密钥），**不在 `main` 与本特性分支**；`security-check.sh` 的历史扫描对这两个路径做了允许。清除需要改写历史，未做。 | 已知，未处理 |
| 7 | **未验证的环境** | 深色模式、真机触控、屏幕阅读器实测、非 Chromium 浏览器（E2E 仅 Chromium）均未验证；原型 README 也注明屏幕阅读器与真机未测。 | 未验证 |
| 8 | **`WAIT 1` 只在无副本环境测试** | 撤销类写入后的 `WAIT 1 <200ms>` 在本仓库的单实例 Redis 上返回 0（没有副本），带副本的行为与主从切换丢失窗口（最后约 1 秒的撤销可能丢失）未测试。 | 未验证 |
| 9 | **Redis / PostgreSQL 单实例** | 本地编排的存储为单实例，自身故障切换未验证；单主 + 高可用、不使用 Redis Cluster 是设计前提（Lua 多键脚本）。 | 设计前提 |
| 10 | **写操作在滚动重启期间** | 持续访问的验证只用 GET；POST（登录、Server Action、退出）在入口不会被重放，重启期间未对写操作做并发验证。 | 未验证 |
| 11 | **退出在 UAM 不可用时的语义** | 退出总是清浏览器 cookie 并回登录页；若 UAM 当时不可用 / 拒绝，UAM 侧会话要等到期才失效（写一条 warn 日志，不向用户声称已终止）。 | 已知，按设计 |
| 12 | **信任模型** | 本期不分角色：任何成功登录的用户都能新建、停用、启用他人并强制下线；服务密钥泄露等于客户端被冒充（可填任意操作人）；仅有应用日志、无可查询审计。UAM 须只在内网可达，这是部署配置。 | 用户已确认的前提 |
| 13 | **入口代理的约束** | `TRUSTED_PROXY_HOPS` 必须等于可信代理跳数；非 80 / 443 端口的入口必须透传带端口的 `Host` 并设 `X-Forwarded-Host`，否则 Server Action 的 Origin / Host 比较失败（`RESULTS.md` 缺陷 C）。 | 部署须知 |

## 9. 实现与原计划（`plan.md` / `data-model.md`）的偏差

以实现为准；`plan.md`、`research.md`、`data-model.md` 作为过程记录保留原文。

| 项目 | 原计划 | 实际 |
|---|---|---|
| Redis 会话 HASH | `ref`、`accountId`、`userId`、`loginAt`、`lastActiveAt`、`absExpiresAt` | 另快照 `userName`、`loginName`、`kind`、`expiresAt`，使 `verify` **只读 Redis、不查 PostgreSQL** |
| 幂等键 | `uam:idem:{sha256(客户端id\|键)}`，保存「键 → 响应」 | 同键，**只缓存 201**；另有进行中标记（30 秒，`P\|指纹`）与完成快照（10 分钟，`D\|指纹\|快照`）；进行中超时 → 409 `idempotency_in_progress`；同键不同请求 → 422 |
| 限流 | 先 `blockedFor` 检查、失败后 `recordFailure` 自增（`throttle-fail.lua`）；5 次连续失败后拒绝 | **哈希比对之前**由 `throttle-admit.lua` 原子占用名额（`LoginThrottle.admit` / `PasswordChangeThrottle.admitChange`），并发错误尝试不会同时通过闸门；前 5 次放行、第 6 次起拒绝；键名不变（`uam:throttle:login:*`、`uam:throttle:pwchg:*`） |
| 登录时哈希升级 | 参数落后时登录成功后升级 | 条件更新 `upgradePasswordHash(accountId, expectedHash, newHash)`，密码已被改过则空操作 |
| 密码长度上限 | 未规定 | 128 个码点（`PasswordPolicy.MAX_LENGTH`） |
| 改密账号校验 | UAM 以「当前密码正确」保护 | 另增：路径账号 ≠ 操作人账号 → 403 `client_forbidden` |
| 权限模型 | 白名单式（登录、校验、退出、改密、写操作需要断言权限） | **按 HTTP 方法**的 `ClientPermissions`：GET / HEAD 只需有效密钥，其余方法需要断言权限；`/actuator/health/**` 免校验，`/actuator/prometheus` 需有效密钥 |
| 被拒绝调用日志 | 来源、时间、原因类别 | 事件 `uam.client.rejected`，`reason ∈ {missing_key, malformed, unknown_key, forbidden}`，每秒至多 200 条、超出只计数（`suppressedBefore`） |
| Admin Center 告警 | 写运维告警日志 | 事件 `admin-center.uam.misconfigured`，同一（错误码，操作）每 60 秒一条，不含密钥 |
| `generate-service-key` | cli profile 下的子命令 | 在 Spring **启动之前**直接执行（`StandaloneCommands`），不连库、不跑 Flyway、不需要任何环境变量；`init-admin` / `add-account` 仍走 `cli` profile |
| `online.lua` | 取全部有效成员，在 UAM 聚合 | 已如此实现，但对每个成员读会话键，是 O(N) 且占用 Redis 单线程（§8-4） |
| domain 新增端口 | `UserStatusLock` 等 | 另有 `ClientAuthenticator`、`ClientRejectionLog`、`TransactionRunner`、`PasswordChangeThrottle`、`IdempotencyStore`；`ClientRegistry` 实现 `ClientAuthenticator`，不再是「只被过滤器直接使用的非领域端口」 |
| 撤销后 `WAIT 1` | 撤销类写入后 | `terminateByRef`（退出、强制下线、登录复查失败时；只有强制下线写墓碑）与 `terminateAllForUser`（停用）后 `WAIT 1 200ms`（`ReplicaWait`）；登录时顶掉旧会话**不** WAIT |
| 退出 | 调 UAM、清 cookie | UAM 不可用 / 拒绝时仍清 cookie（§8-11） |
| 界面 | — | 「最后一个正常用户」入口不预先禁用（见 FR-025 行）；用户菜单非模态（§8-1） |
