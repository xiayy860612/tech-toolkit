# Data Model: Tenant Core MVP

PostgreSQL，**单一 schema（`public`）**。package 边界只体现在 Java 包（`tenant`、`uam`、`audit`）和**表名前缀**（`core_`、`uam_`、`audit_`）上；后期拆分服务时，按前缀把对应的表整体迁出到各自的库（2026-11-07 决定）。

为使拆分顺畅，约束两条：

- **package 之间不建外键、不做 SQL 联表**。`uam_*` 与 `audit_event` 里的 `tenant_id` 只是普通列，由服务层校验；需要跨 package 数据时（如 User 页显示 Tenant Slug）在服务层经接口取回再组装。
- **外键只在同一 package 内**。
- 本文列出的格式、长度、枚举约束同时由数据库 CHECK 强制；服务层先校验并给出友好错误。

时间一律 `timestamptz`（UTC）。主键为 UUID，对外可见的 User ID 为 `tu_` + 短随机串（`tenant_user_id`，D8）。

## 关系

```text
core_tenant 1 ─── * core_module_subscription
（tenant_id 为逻辑引用，非外键）
core_tenant 1 ··· * uam_tenant_login_method
core_tenant 1 ··· * uam_user 1 ─── * uam_account 1 ─── 0..1 uam_credential_password
                                   uam_account 1 ─── * uam_opaque_token   （Token 属于登录所用的 Account，并带出其 User）
audit_event（独立追加表，tenant_id 可空，无外键）
```

## core_tenant

| 字段 | 类型 | 约束 |
|---|---|---|
| id | uuid | PK |
| slug | text | 唯一；`^[a-z][a-z0-9-]{2,31}$`；创建后不可变 |
| name | text | 非空，≤ 100 |
| status | text | `ACTIVE` \| `SUSPENDED`，默认 `ACTIVE` |
| created_at | timestamptz | 非空 |

## core_module_subscription

| 字段 | 类型 | 约束 |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | FK → core_tenant（同一 package） |
| module_code | text | CHECK ∈ (`platform`)（MVP） |
| status | text | `SUBSCRIBED`（MVP 仅此值） |
| subscribed_at | timestamptz | 非空 |

唯一 (tenant_id, module_code)。

## uam_tenant_login_method

| 字段 | 类型 | 约束 |
|---|---|---|
| tenant_id | uuid | 非空；逻辑引用 core_tenant，非外键 |
| method_type | text | CHECK ∈ (`PASSWORD`) |
| enabled_at | timestamptz | 非空 |

PK (tenant_id, method_type)。

## uam_user

“一个人”，业务身份；下游只认 `tenant_user_id`（D8）。

| 字段 | 类型 | 约束 |
|---|---|---|
| id | uuid | PK |
| tenant_user_id | text | 唯一，`tu_…` |
| tenant_id | uuid | 非空；逻辑引用，非外键 |
| display_name | text | 必填，≤ 100；MVP 的 CLI 幂等键为 (tenant_id, display_name)，仅在 CLI 逻辑中校验，不加数据库唯一约束 |
| status | text | `ACTIVE` \| `DISABLED`，默认 `ACTIVE` |
| created_at | timestamptz | 非空 |

## uam_account

登录凭据的载体（即设计文档中的 identity，D17/D19）；关联且仅关联一个 User，一个 User 可有多个 Account。

| 字段 | 类型 | 约束 |
|---|---|---|
| id | uuid | PK |
| user_id | uuid | FK → uam_user，非空（同一 package） |
| tenant_id | uuid | 冗余，便于唯一约束；须与 User 的 Tenant 一致 |
| type | text | `PASSWORD`（MVP） |
| provider | text | 密码类型固定为空串 |
| subject | text | username；Tenant 内唯一，存小写，`^[a-z0-9][a-z0-9._-]{2,63}$` |
| created_at | timestamptz | 非空 |

唯一 (tenant_id, type, provider, subject)（D19）。MVP 另加部分唯一约束：每个 User 至多一个 `PASSWORD` Account。

## uam_credential_password

| 字段 | 类型 | 约束 |
|---|---|---|
| account_id | uuid | PK, FK → uam_account |
| password_hash | text | Argon2id 编码串，永不输出 |
| must_change_password | boolean | CLI 创建时为 true；改密后为 false |
| updated_at | timestamptz | 非空 |
| failed_count | int | 默认 0 |
| locked_until | timestamptz | 可空 |

## uam_opaque_token

| 字段 | 类型 | 约束 |
|---|---|---|
| token_hash | bytea | PK，SHA-256；明文 Token 只在签发时返回一次 |
| sid | text | 唯一，`s_` + 随机串；Session 标识，写入 Internal JWT 的 `sid` |
| account_id | uuid | FK → uam_account，登录所用 Account |
| user_id | uuid | FK → uam_user，Account 所关联的 User |
| tenant_id | uuid | 冗余 |
| created_at | timestamptz | 非空 |
| last_seen_at | timestamptz | 滑动过期用 |
| expires_at | timestamptz | = created_at + 8h（绝对上限） |
| revoked_at | timestamptz | 可空 |

有效条件：未吊销、未过 `expires_at`、`last_seen_at` + 30 分钟 > now。

## audit_event

| 字段 | 类型 | 说明 |
|---|---|---|
| id | uuid | PK |
| occurred_at | timestamptz | |
| actor | text | `cli:<os-user>@<host>` \| `tu_…` \| `anonymous` |
| action | text | `tenant.create`、`login-method.enable`、`user.create`、`account.create`、`subscription.add`、`login.success`、`login.failure`、`password.change`、`logout`、`token.exchange` |
| tenant_id | uuid | 可空（如 Tenant 不存在的登录失败，请求里的 slug 记入 `target`）；无外键 |
| target | text | 目标描述，不含密码/哈希/Token |
| outcome | text | `SUCCESS` \| `FAILURE` \| `NOOP` |
| trace_id | text | 可空 |

只追加：迁移从应用角色 `tenant_core_app` 撤销 `audit_event` 的 UPDATE、DELETE、TRUNCATE（这是仅有的角色级措施，不按 package 拆数据库角色）。

## Internal JWT（不入库，仅配置）

Internal JWT 由 UAM 签发与校验，契约沿用 `docs/system-design/02-authentication.md` 4.2 节：

| 配置 | 说明 |
|---|---|
| `UAM_ISSUER` | `iss`；校验时固定取配置，不从 Token 动态获取 |
| `UAM_AUDIENCE` | `aud`，默认 `platform-services`（D13） |
| `UAM_CELL_ID` | `cell_id` |
| `UAM_SIGNING_KEY_PEM` | ES256 私钥 PEM（环境变量或挂载的密钥文件，不入库）；`kid` 取公钥指纹；`dev` profile 未配置时生成临时密钥，其他 profile 缺失则启动失败 |
| `UAM_JWT_TTL_SECONDS` | 默认 120（设计建议 1–5 分钟） |

声明：`iss`、`aud`、`sub`（= `tenant_user_id`）、`exp`、`iat`、`jti`、`tenant_id`、`cell_id`、`client_type`（`web`）、`azp`（`platform-web`）、`sid`。MVP 不写 `scope`（无粗粒度范围需求）；`must_change_password` 状态不进 Token——要求改密的 Session**不允许换发**。`sid` 用于把 Internal JWT 关联回 `uam_opaque_token`，供 `/me` 取 Account 信息与审计。

## 状态与规则

- **登录准入**（FR-012），分两步：
  1. 按 (Tenant Slug, username) 找到 Account → 其 User；要求 Tenant 存在且 `ACTIVE` ∧ `PASSWORD` 已启用 ∧ User `ACTIVE` ∧ Account 未锁定 ∧ 密码正确。任一不满足，对外统一为 INVALID_CREDENTIALS（401）；Account 不存在时仍执行一次哑哈希校验。
  2. username 与密码验证通过后，才检查 Tenant 是否存在 `platform` 订阅；没有则返回 MODULE_NOT_SUBSCRIBED（403）。
- **首次改密**：Account 的 `must_change_password` 为 true 时，Opaque Token 仅限改密/退出/自身查询；改密后置 false 并轮换 Token。
- **锁定**：按 Account 计数，连续失败 5 次 → `locked_until = now + 15 min`；成功登录清零；锁定期间仍返回统一的 INVALID_CREDENTIALS。
- **Token 换发**：仅用有效、未要求改密的 Opaque Token 可换发；换发写 `token.exchange` Audit。Internal JWT 吊销延迟最长为其 `exp`。
- **创建 Account**：User 必须属于同一 Tenant；Tenant 须已启用对应 Login Method。
- **幂等键**：Tenant `slug`；Login Method (tenant, type)；User (tenant, display_name)；Account (tenant, type, subject)，且每 User 一个密码 Account；订阅 (tenant, module)。
