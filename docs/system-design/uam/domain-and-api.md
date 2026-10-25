# UAM — 领域模型、接口与业务流程（现状归档）

> **归档状态**：特性 `002-admin-center-uam` 已交付并归档。本文是UAM 的领域模型、接口与权限、业务流程的当前真实状态（技术性内容，故在 `docs/system-design/`；`docs/achieve-specs/admin-center/` 只放用户需求描述 [spec.md](../../achieve-specs/admin-center/spec.md)）。章节编号沿用归档前的合订本，便于对照：§3–4、§5.1、§5.3、§5.4 在本文；其余见 [需求对照与验证记录](../system/traceability.md)。
> 本文只记录能在代码、测试、脚本或文档中核实的内容。

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

- **User**：`ACTIVE → DISABLED`（停用，校验自我保护，提交后终止其会话）；`DISABLED → ACTIVE`（启用，**不恢复**已结束会话）；重复操作幂等。图见 [domain-state-user.svg](./diagrams/domain-state-user.svg)。
- **Session**：登录成功 → 有效；退出 / 强制下线 / 用户停用 / 被取代 / 空闲到期 / 绝对到期 → 结束（终态）；其中**只有管理员强制下线**会在终止的同时留下 10 分钟的墓碑 `uam:term:{sha}`，其余结束方式不留。图见 [domain-state-session.svg](./diagrams/domain-state-session.svg)。
- 类图见 [domain-class.svg](./diagrams/domain-class.svg)。

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
| **登录** | Admin Center 同源校验、必填校验（为空不调用 UAM）、按 `TRUSTED_PROXY_HOPS` 取终端来源地址（取不到则拒绝）→ UAM：原子占用登录限流名额（超限 429）→ 读账号 / 用户 / 凭据 → **总是先做一次哈希比对**（不存在则比对伪哈希）→ 再判账号与用户状态，任一不满足统一 `invalid_credentials` → `login.lua` 写新会话并顶掉旧会话 → **复查用户状态**（已停用则终止刚建会话并拒绝）→ 清零该来源的计数 → 返回会话标识与会话数据。（哈希需要升级时用**条件更新** `upgradePasswordHash`：库里的哈希仍是比对时读到的那一个才替换，避免旧密码的升级结果覆盖刚改的新密码。）图：[seq-login.svg](../system/diagrams/seq-login.svg)。 |
| **校验（每个浏览器请求）** | Admin Center 对每个有 cookie 的请求调 `verify`；UAM 只读 `uam:sess:{sha256(id)}`（HASH 内已快照用户名、账号名、种类、到期，**不访问 PostgreSQL**）；无效 / 过期 / 被取代 / 被终止 / 过长或空白 → 401 `session_invalid`（会话不存在时再按所呈现标识的哈希查 `uam:term:{sha}`，命中即被管理员强制下线 → body 带 `reason: terminated`，否则不带）；距上次写入 > 60 秒才 `touch.lua` 续期。会话一结束，下一次请求即判未登录（窗口为 0）。图：[seq-verify-and-call.svg](../system/diagrams/seq-verify-and-call.svg)。 |
| **退出 / 到期** | `logout`：按会话标识找到会话引用后 `terminate.lua`（含 `WAIT 1`），幂等，不写 `uam.admin.write`。到期：Redis TTL 删除会话键；`uam:online` 的过期条目由列表脚本的 `ZREMRANGEBYSCORE` 清理并以到期分数 / `expiresAt` 保证不被计入。 |
| **停用用户** | 事务内取 `pg_advisory_xact_lock` → 读目标 → `SelfProtectionRule.checkDisable`（自己 / 最后一个）→ 置 `DISABLED`；**提交后**对其全部账号 `terminate-all.lua`（含 `WAIT 1`）。对已停用用户重复停用同样成功并再次确保会话已终止。账号自身状态不变。 |
| **启用用户** | 事务内取同一把锁并置 `ACTIVE`；**不恢复**会话；幂等。 |
| **强制下线** | `SelfProtectionRule.checkTerminate` → `terminate.lua`（按 `uam:sref:{ref}`，`acct` 指针仅在仍指向该会话时才删；同一脚本里原子写墓碑 `uam:term:{sha}`，TTL `uam.session.terminated-notice-ttl` 默认 10 分钟）→ `WAIT 1`；目标不存在 → 404 `session_not_found`。只终止该会话，不改账号与用户状态；被下线者之后可再次登录。图：[seq-terminate-session.svg](../system/diagrams/seq-terminate-session.svg)。 |
| **新建用户** | 校验 `Idempotency-Key` → 抢占 `uam:idem:{sha256(clientId\|key)}`（`SET NX`）→ `AccountProvisioningService` 单事务创建用户 + 账号 + 凭据（账号名冲突 409 `account_name_taken`，规则校验失败 422 `validation_failed`，请求结构不合法 400）→ 成功缓存响应快照（不含密码 / 哈希）→ 写日志；被拒绝 / 失败释放占位。 |
| **修改密码** | 路径账号须等于操作人账号 → 原子占用改密限流名额（超限 429）→ **总是做一次哈希比对**（账号不存在比对伪哈希）→ 当前密码错 `current_password_incorrect`（占用的名额即计为一次失败）→ 当前密码正确则清零 → 新密码强度（`weak_password`）与不同于当前（`same_as_current`）→ 只更新该账号的哈希。不触碰会话，不轮换会话标识（账号与会话一对一）。 |
| **服务密钥轮换** | 见 §5.3。 |

### 5.3 服务密钥轮换（FR-044）

1. `generate-service-key --client admin-center` 生成新密钥（标准输出只出现一次，哈希与 `UAM_CLIENTS` 片段写标准错误）；该命令在 Spring 启动**之前**执行，不连库、不连 Redis、不需要环境变量。
2. 把**新哈希加为第二个** `keyHashes`，滚动发布 UAM（此时同一客户端 2 个有效哈希；超过 2 个或重复则 UAM 启动失败）。
3. Admin Center 换新 `UAM_SERVICE_KEY`，滚动发布。
4. 从 `UAM_CLIENTS` 删旧哈希，再滚动发布 UAM；之后旧密钥 → 401。

本地演练：`infra/local/scripts/rotate-key.sh rotate`。

### 5.4 在线用户与会话查询

`GET /v1/online-users` 由 `online.lua` 清理到期条目并**扫描整个 `uam:online`**，UAM 内按用户聚合（会话数、最近活动），分页后回 PostgreSQL 批量取用户名。`GET /v1/users/{id}/sessions` 取该用户全部账号，逐个读 `uam:acct:{accountId}:sess`（一对一，账号数极少）。已知的规模上限见 [traceability §8](../system/traceability.md)。
