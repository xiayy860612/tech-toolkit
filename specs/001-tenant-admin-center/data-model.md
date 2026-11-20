# Data Model: Tenant Admin Center

**Feature**: [spec.md](spec.md) · **Research**: [research.md](research.md)

所有 id 为 UUID；时间为 UTC `timestamptz`；所有表带 `tenant_id bigint not null` 并启用 Row-Level Security（R3）。**本期不涉及租户管理：没有 `tenant` 表，`tenant_id` 恒为 0**；隔离能力照常实现，用测试夹具中的另一租户（id=1）验证。迁移由 Flyway `V1__init.sql` 管理。

## 实体关系

```text
（tenant_id = 0）User 1 ──< Account 1 ──1 PasswordCredential   (首期；其他类型各有自己的凭证表)
   ├──< Session (发起登录的 Account；同属一个 User 与 Tenant)
   └──< AuditLog  (actor 为 User，可为空：登录失败时无已知 User)
```

## User（`app_user`）

| 字段 | 类型 | 约束 / 说明 |
|---|---|---|
| id | uuid | PK |
| tenant_id | bigint | 非空，本期恒为 0（无 tenant 表） |
| display_name | text | 非空，≤ 50 |
| phone | text | 可空，格式校验（11 位大陆手机号） |
| email | text | 可空，邮箱格式 |
| role | enum | `TENANT_ADMIN` / `MEMBER` |
| status | enum | `ACTIVE` / `DISABLED` |
| version | bigint | 乐观锁（R11） |
| created_at / updated_at | timestamptz | |

- **不持有**密码或登录标识（FR-001）。
- 索引：`(tenant_id, status, display_name)`；`(tenant_id, lower(display_name))` 供关键字前缀搜索。
- 表名用 `app_user` 避开 SQL 保留字。

## Account（`account`）

| 字段 | 类型 | 约束 / 说明 |
|---|---|---|
| id | uuid | PK |
| tenant_id | bigint | 非空，本期恒为 0；与所属 User 的 tenant_id 一致（复合外键 `(tenant_id, user_id) → app_user(tenant_id, id)` 保证） |
| user_id | uuid | 非空，一个 Account 只属于一个 User（FR-002） |
| type | enum | 首期仅 `USERNAME_PASSWORD`（FR-003） |
| identifier | text | 登录标识（用户名）原样保存，用于展示；≤ 64 |
| identifier_normalized | text | NFKC + 小写；`UNIQUE (tenant_id, type, identifier_normalized)`（FR-014） |
| status | enum | `ACTIVE` / `DISABLED` |
| failed_attempts | int | 默认 0（R8） |
| locked_until | timestamptz | 可空；`> now()` 表示锁定中 |
| last_login_at | timestamptz | 可空 |
| version | bigint | 乐观锁 |
| created_at / updated_at | timestamptz | |

- 用户名规则：3–32 位，字母/数字/`.`/`_`/`-`，以字母或数字开头。
- 一个 User 可有多个 Account；"可用 Account" = `status = ACTIVE` 且所属 User `ACTIVE`。
- 索引：`(tenant_id, user_id)`。

## PasswordCredential（`password_credential`）

| 字段 | 类型 | 约束 / 说明 |
|---|---|---|
| account_id | uuid | PK，FK → account |
| tenant_id | bigint | 非空，RLS |
| password_hash | text | Argon2id 编码串（含参数与盐），**不可逆**（FR-006） |
| must_change_password | boolean | 临时密码创建/重置后为 `true`（FR-011a） |
| password_changed_at | timestamptz | |

- 新增 Account 类型 = 新增对应凭证表，不修改 `app_user`（SC-007）。

## Session（`session`）

会话由**后端统一维护**（research R4）；BFF 只持有后端签发的不透明令牌（Cookie），不保存会话数据。

| 字段 | 类型 | 约束 / 说明 |
|---|---|---|
| id | uuid | PK；**会话 id，不是令牌**，可在会话管理页和 JWT 的 `sid` 中使用 |
| tenant_id | bigint | 非空，RLS |
| token_hash | bytea | 令牌（256 位随机，一次性交给 BFF）的 SHA-256，唯一；令牌明文不落库 |
| user_id | uuid | 非空 |
| account_id | uuid | 非空，发起登录的 Account |
| created_at | timestamptz | |
| last_seen_at | timestamptz | 滑动空闲超时依据，每分钟最多写一次 |
| absolute_expires_at | timestamptz | `created_at + 8h`（可配置） |
| revoked_at | timestamptz | 可空 |
| revoke_reason | enum | `LOGOUT` / `USER_DISABLED` / `ACCOUNT_DISABLED` / `ACCOUNT_DELETED` / `PASSWORD_RESET` / `PASSWORD_CHANGED` / `ADMIN_REVOKED` |
| ip | inet | 登录时的可信客户端 IP（来自 service JWT 的 `cip`） |
| user_agent | text | 原始 UA，≤ 512，**不对外返回** |
| device | text | 由 UA 解析的设备摘要，如「Chrome 130 / macOS」，对外展示 |

- **有效** = `revoked_at IS NULL` 且 `now() < absolute_expires_at` 且 `now() - last_seen_at < idle_timeout`（默认 30 分钟）。
- 索引：`token_hash`（唯一）、`(tenant_id, last_seen_at DESC) WHERE revoked_at IS NULL`、`(user_id) WHERE revoked_at IS NULL`、`(account_id) WHERE revoked_at IS NULL`。
- 后端每个带用户 JWT 的请求：按 `sid` 取会话并校验有效，且与 `sub`/`tenant_id`/`account_id` 一致，同时核对 User 与 Account 均为 `ACTIVE`；任一不满足 → 401。过期与已撤销超过保留期（默认 7 天，可配置）的会话由后端定时任务清理。

## AuditLog（`audit_log`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | uuid | PK |
| tenant_id | bigint | 非空 |
| occurred_at | timestamptz | |
| actor_user_id / actor_account_id | uuid | 可空（登录失败） |
| action | text | 见下表 |
| target_type | text | `USER` / `ACCOUNT` |
| target_id | uuid | 可空 |
| result | enum | `SUCCESS` / `FAILURE` |
| reason | text | 失败或被拒原因（如 `LOCKED`、`ACCOUNT_DISABLED`、`BAD_PASSWORD`），**仅审计可见**，不返回给登录方 |
| ip | inet | |
| detail | jsonb | 已脱敏的变更摘要，**永不含密码/令牌** |

- 审计属基础服务能力，**必须租户隔离**：带 `tenant_id` 并启用 RLS。首期内置于本服务（经 `AuditSink` 端口，R17）。只 `INSERT/SELECT`，触发器拒绝 `UPDATE/DELETE`（R10）。索引 `(tenant_id, occurred_at DESC)`。
- `action` 枚举：`LOGIN_SUCCESS`、`LOGIN_FAILURE`、`LOGOUT`、`PASSWORD_CHANGED`、`ACCOUNT_LOCKED`、`ACCOUNT_UNLOCKED`、`USER_CREATED`、`USER_UPDATED`、`USER_DISABLED`、`USER_ENABLED`、`ACCOUNT_CREATED`、`ACCOUNT_DISABLED`、`ACCOUNT_ENABLED`、`ACCOUNT_DELETED`、`PASSWORD_RESET`、`SESSION_REVOKED`、`SESSIONS_REVOKED_ALL`（目标为被下线的 `USER`，`detail` 记范围/数量/`sessionRef`，不含真实 session id）。

## 状态与转换

**User**：`ACTIVE ⇄ DISABLED`。停用 → 撤销其全部会话（`USER_DISABLED`）；其所有 Account 无法登录；重新启用后旧会话**不会**复活（已撤销）。

**Account**：`ACTIVE ⇄ DISABLED`；另有正交的"锁定"状态（`locked_until`）。
- 停用 → 撤销该 Account 的全部会话（`ACCOUNT_DISABLED`）；删除 → 物理删除凭证与 Account，并撤销其会话（`ACCOUNT_DELETED`）。
- 重置密码 → 写入新哈希、`must_change_password = true`、撤销该 Account 全部会话（`PASSWORD_RESET`）。
- 修改密码 → 写入新哈希、`must_change_password = false`，撤销该 Account **除当前会话外**的全部会话（`PASSWORD_CHANGED`，FR-011b）。
- 解锁 → `failed_attempts = 0, locked_until = NULL`。
- 管理员强制下线 → 撤销指定会话或某 User 的全部会话（`ADMIN_REVOKED`），不能撤销自己的当前会话。

**登录判定顺序**（任一不满足 → 对外统一 401 `INVALID_CREDENTIALS`，真实原因只写审计）：Account 存在 → Account `ACTIVE` → User `ACTIVE` → 未锁定 → 密码匹配。失败累加 `failed_attempts`，达阈值设置 `locked_until`；成功清零、更新 `last_login_at`，并创建会话（返回一次性令牌与到期时间）。

**强制改密**：`must_change_password = true` 时，后端对用户 JWT 请求只放行 `GET /auth/me`、`POST /auth/change-password`、`POST /auth/logout`，其余返回 403 `PASSWORD_CHANGE_REQUIRED`（以数据库为准）。

## 不变量（由事务 + 行锁 + 约束共同保证）

| 不变量 | 对应需求 | 保证方式 |
|---|---|---|
| 租户至少保留 1 名 `ACTIVE` 的 `TENANT_ADMIN` | FR-021 | 停用/降级/删除前 `FOR UPDATE` 锁该租户管理员行，计数后再提交；否则 409 `LAST_ADMIN` |
| 管理员不能停用自己 | Edge Case | 409 `CANNOT_DISABLE_SELF` |
| User 不会在无确认下失去最后一个可用 Account | FR-021 | 删除/停用最后一个可用 Account 需 `confirm=true`，否则 409 `LAST_ACTIVE_ACCOUNT_CONFIRM_REQUIRED` |
| 用户名租户内唯一（大小写不敏感） | FR-014 | 唯一约束，冲突 409 `USERNAME_TAKEN` |
| 跨租户不可见、不可改 | FR-013, SC-003 | 应用层租户条件 + RLS；跨租户 id 一律表现为 404 |
| 同一 Account 只属一个 User、且与其同租户 | FR-002 | 复合外键 |
