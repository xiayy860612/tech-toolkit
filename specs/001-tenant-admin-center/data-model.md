# Data Model: Tenant Admin Center

**Feature**: [spec.md](spec.md) · **Research**: [research.md](research.md)

主键：`app_user`、`audit_log` 用递增整型（`bigint GENERATED ALWAYS AS IDENTITY`，FR-004a）；**会话 id 仍为 UUID**（对外公开的标识，不可被枚举）。时间为 UTC `timestamptz`；所有表带 `tenant_id bigint not null` 并启用 Row-Level Security（R3）。**本期不涉及租户管理：没有 `tenant` 表，`tenant_id` 恒为 0**；隔离能力照常实现，用测试夹具中的另一租户（id=1）验证。迁移由 Flyway `V1__init.sql` 管理（项目尚未发布，本次把原 V1/V2 合并重写，开发库重建，不写数据迁移）。

## 实体关系

```text
（tenant_id = 0）User ──内置──  用户名 + 密码凭证 + 失败锁定（FR-001，每个 User 必然有）
   ├──< Session (记录登录方式 login_type；同属一个 User 与 Tenant)
   ├──< AuditLog (actor 为 User，可为空：登录失败时无已知 User)
   └──< 其他登录方式 Account（日后：手机号、微信、OAuth2.0；每种类型一张自己的表，
        通过 user_id 关联到 User，每种类型 UNIQUE (tenant_id, user_id)，首期没有任何此类表）
```

## User（`app_user`）

| 字段 | 类型 | 约束 / 说明 |
|---|---|---|
| id | bigint | PK，自增 |
| tenant_id | bigint | 非空，本期恒为 0（无 tenant 表） |
| display_name | text | 非空，≤ 50 |
| role | enum | `TENANT_ADMIN` / `MEMBER` |
| status | enum | `ACTIVE` / `DISABLED` |
| username | text | 非空，登录用户名原样保存，用于展示；≤ 64（规则：3–32 位，字母/数字/`.`/`_`/`-`，以字母或数字开头） |
| username_normalized | text | 非空，NFKC + 小写；`UNIQUE (tenant_id, username_normalized)`（FR-014） |
| password_hash | text | 非空，Argon2id 编码串（含参数与盐），**不可逆**（FR-006） |
| must_change_password | boolean | 临时密码创建/重置后为 `true`（FR-011a） |
| password_changed_at | timestamptz | 可空 |
| failed_attempts | int | 默认 0（R8） |
| locked_until | timestamptz | 可空；`> now()` 表示锁定中 |
| last_login_at | timestamptz | 可空 |
| version | bigint | 乐观锁（R11） |
| created_at / updated_at | timestamptz | |

- **用户名密码是 User 自身的一部分**（FR-001），不是 Account；`password_hash` 永不出现在任何响应、日志、审计里。
- `UNIQUE (tenant_id, id)` 供其他表的复合外键使用；索引：`(tenant_id, status, display_name)`；`(tenant_id, lower(display_name))` 供关键字前缀搜索。
- 表名用 `app_user` 避开 SQL 保留字。

## 其他登录方式 Account（日后，首期无表）

- 每种 Account 类型使用**自己的表**（如将来的 `account_phone`、`account_wechat`、`account_oauth_<provider>`），各带 `id bigint` 自增、`tenant_id`、`user_id`，复合外键 `(tenant_id, user_id) → app_user(tenant_id, id)`，并以 `UNIQUE (tenant_id, user_id)` 保证**每个 User 对每种类型至多一个**（FR-002）；各类型自己的登录标识加租户内唯一约束。
- 新增类型 = 新表 + 新 `Authenticator` 实现，**不修改 `app_user`**（SC-007）。违反"每类型至多一个"时 409 `ACCOUNT_TYPE_ALREADY_LINKED`。

## Session（`session`）

会话由**后端统一维护**（research R4）；BFF 只持有后端签发的不透明令牌（Cookie），不保存会话数据。

| 字段 | 类型 | 约束 / 说明 |
|---|---|---|
| id | uuid | PK；**会话 id，不是令牌**，可在会话管理页和 JWT 的 `sid` 中使用 |
| tenant_id | bigint | 非空，RLS |
| token_hash | bytea | 令牌（256 位随机，一次性交给 BFF）的 SHA-256，唯一；令牌明文不落库 |
| user_id | bigint | 非空 |
| login_type | text | 非空，登录方式；首期恒为 `USERNAME_PASSWORD`，日后随 Account 类型扩展 |
| created_at | timestamptz | |
| last_seen_at | timestamptz | 滑动空闲超时依据，每分钟最多写一次 |
| absolute_expires_at | timestamptz | `created_at + 8h`（可配置） |
| revoked_at | timestamptz | 可空 |
| revoke_reason | enum | `LOGOUT` / `USER_DISABLED` / `PASSWORD_RESET` / `PASSWORD_CHANGED` / `ADMIN_REVOKED` |
| ip | inet | 登录时的可信客户端 IP（来自 service JWT 的 `cip`） |
| user_agent | text | 原始 UA，≤ 512，**不对外返回** |
| device | text | 由 UA 解析的设备摘要，如「Chrome 130 / macOS」，对外展示 |

- **有效** = `revoked_at IS NULL` 且 `now() < absolute_expires_at` 且 `now() - last_seen_at < idle_timeout`（默认 30 分钟）。
- 索引：`token_hash`（唯一）、`(tenant_id, last_seen_at DESC) WHERE revoked_at IS NULL`、`(user_id) WHERE revoked_at IS NULL`。
- 后端每个带用户 JWT 的请求：按 `sid` 取会话并校验有效，且与 `sub`/`tenant_id` 一致，同时核对 User 为 `ACTIVE`；任一不满足 → 401。过期与已撤销超过保留期（默认 7 天，可配置）的会话由后端定时任务清理。

## AuditLog（`audit_log`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | PK，自增 |
| tenant_id | bigint | 非空 |
| occurred_at | timestamptz | |
| actor_user_id | bigint | 可空（登录失败） |
| action | text | 见下表 |
| target_type | text | `USER` |
| target_id | bigint | 可空 |
| result | enum | `SUCCESS` / `FAILURE` |
| reason | text | 失败或被拒原因（如 `LOCKED`、`USER_DISABLED`、`BAD_PASSWORD`），**仅审计可见**，不返回给登录方 |
| ip | inet | |
| detail | jsonb | 已脱敏的变更摘要，**永不含密码/令牌** |

- 审计属基础服务能力，**必须租户隔离**：带 `tenant_id` 并启用 RLS。首期内置于本服务（经 `AuditSink` 端口，R17）。只 `INSERT/SELECT`，触发器拒绝 `UPDATE/DELETE`（R10）。索引 `(tenant_id, occurred_at DESC)`。
- `action` 枚举：`LOGIN_SUCCESS`、`LOGIN_FAILURE`、`LOGOUT`、`PASSWORD_CHANGED`、`USER_LOCKED`、`USER_UNLOCKED`、`USER_CREATED`、`USER_UPDATED`、`USER_DISABLED`、`USER_ENABLED`、`PASSWORD_RESET`、`SESSION_REVOKED`、`SESSIONS_REVOKED_ALL`（目标为被下线的 `USER`，`detail` 记范围/数量/`sessionRef`，不含真实 session id）。

## 状态与转换

**User**：`ACTIVE ⇄ DISABLED`。停用 → 撤销其全部会话（`USER_DISABLED`），无法以任何方式登录；重新启用后旧会话**不会**复活（已撤销）。
- 重置密码 → 写入新哈希、`must_change_password = true`、撤销该 User 全部会话（`PASSWORD_RESET`）。
- 修改密码 → 写入新哈希、`must_change_password = false`，撤销该 User **除当前会话外**的全部会话（`PASSWORD_CHANGED`，FR-011b）。
- 另有正交的"锁定"状态（`locked_until`）：解锁 → `failed_attempts = 0, locked_until = NULL`。
- 管理员强制下线 → 撤销指定会话或某 User 的全部会话（`ADMIN_REVOKED`），不能撤销自己的当前会话。

**登录判定顺序**（任一不满足 → 对外统一 401 `INVALID_CREDENTIALS`，真实原因只写审计）：User 存在（按租户内规范化用户名）→ User `ACTIVE` → 未锁定 → 密码匹配。失败累加 `failed_attempts`，达阈值设置 `locked_until`；成功清零、更新 `last_login_at`，并创建会话（返回一次性令牌与到期时间）。

**强制改密**：`must_change_password = true` 时，后端对用户 JWT 请求只放行 `GET /auth/me`、`POST /auth/change-password`、`POST /auth/logout`，其余返回 403 `PASSWORD_CHANGE_REQUIRED`（以数据库为准）。

## 不变量（由事务 + 行锁 + 约束共同保证）

| 不变量 | 对应需求 | 保证方式 |
|---|---|---|
| 每个 User 必然有用户名与密码 | FR-001 | `username`、`username_normalized`、`password_hash` 均 `NOT NULL` |
| 租户至少保留 1 名 `ACTIVE` 的 `TENANT_ADMIN` | FR-021 | 停用/降级前 `FOR UPDATE` 锁该租户管理员行，计数后再提交；否则 409 `LAST_ADMIN` |
| 管理员不能停用自己 | FR-021a | 409 `CANNOT_DISABLE_SELF` |
| 管理员不能修改自己的角色 | FR-021a | 编辑自己时 `role` 与库中不同 → 409 `CANNOT_CHANGE_OWN_ROLE`；角色未变的其它字段照常保存 |
| 用户名租户内唯一（大小写不敏感） | FR-014 | 唯一约束，冲突 409 `USERNAME_TAKEN` |
| 跨租户不可见、不可改 | FR-013, SC-003 | 应用层租户条件 + RLS；跨租户 id 一律表现为 404 |
| 每个 User 对每种 Account 类型至多一个 | FR-002 | 各类型表上 `UNIQUE (tenant_id, user_id)`，违反 409 `ACCOUNT_TYPE_ALREADY_LINKED`（日后） |
