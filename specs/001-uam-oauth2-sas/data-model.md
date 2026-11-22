# Data Model: auth

数据库 PostgreSQL 18，Flyway 迁移。表名前缀 `auth_`（自有表）；`oauth2_*` 与 `spring_session*` 沿用框架的标准表结构，不改列。

## 自有表

### auth_user

| 列 | 类型 | 约束 / 说明 |
|---|---|---|
| id | bigint identity | 主键；令牌 `sub` = 十进制字符串 |
| username | varchar(64) | 唯一，**小写存储**；字符集 `[a-z0-9._-]`，长度 3–64 |
| password_hash | varchar(255) | argon2id；不对外读取 |
| status | varchar(16) | `ENABLED` / `DISABLED` |
| is_admin | boolean | 默认 false；管理员角色（FR-019a） |
| must_change_password | boolean | 默认 false；管理员重置、bootstrap 创建后为 true |
| failed_attempts | int | 默认 0；登录成功清零 |
| locked_until | timestamptz | 可空；`> now()` 即处于锁定 |
| password_changed_at | timestamptz | 改密时间；用于使旧授权失效的判断 |
| created_at / updated_at | timestamptz | |

状态：`ENABLED ⇄ DISABLED`；“锁定”不是状态，由 `locked_until` 表达。禁用、改密、重置密码都会删除该用户的全部授权与 SSO 会话（FR-012）。

### auth_client_ext

补充 `oauth2_registered_client` 缺少的字段，一行对应一个 client。

| 列 | 类型 | 说明 |
|---|---|---|
| client_id | varchar(100) | 主键，对应 `oauth2_registered_client.client_id` |
| enabled | boolean | 默认 true；停用后拒绝授权与刷新，并删除其授权（FR-012、US5） |
| backchannel_logout_uri | varchar(2000) | 可空；https（开发环境允许 http 回环）；为空则不通知 |
| post_logout_redirect_uris | text | 登记的退出后地址，换行分隔，精确匹配 |
| access_token_ttl_seconds / refresh_token_ttl_seconds | int | 可空；空则用全局默认（FR-013） |

`oauth2_registered_client` 里存：client_id、哈希后的 secret、回调地址、scope、授权类型（仅 `authorization_code`、`refresh_token`）、PKCE 强制、**不要求同意页**。公开 client 无 secret。

### auth_sso_client

记录一个 SSO 会话服务过哪些 client，用于退出时通知。

| 列 | 类型 | 说明 |
|---|---|---|
| session_id | varchar(64) | 这次登录的 `sid`：HTTP 会话 id 的 SHA-256 哈希（base64url，43 个字符），与 Spring Authorization Server 写进 ID token 的 `sid` 同一算法 |
| client_id | varchar(100) | |
| first_seen_at | timestamptz | |

主键 (session_id, client_id)。会话结束后随会话清理。

### auth_logout_delivery

| 列 | 类型 | 说明 |
|---|---|---|
| id | bigint identity | |
| client_id | varchar(100) | |
| session_id | varchar(64) | `sid`，同上 |
| attempts | int | |
| next_attempt_at | timestamptz | 指数退避 |
| status | varchar(16) | `PENDING` / `DELIVERED` / `GAVE_UP` |

只保存元数据，不保存 `logout_token` 原文；每次投递重新签发。`attempts` 是已尝试次数：首次投递加 5 次重试（间隔 1s、5s、30s、2min、10min），共最多 6 次，之后记 `GAVE_UP`。

### auth_audit_event

| 列 | 类型 | 说明 |
|---|---|---|
| id | bigint identity | |
| occurred_at | timestamptz | |
| type | varchar(48) | 见下 |
| actor | varchar(100) | 管理员或用户 id，可空（匿名失败登录） |
| subject_user_id | bigint | 可空 |
| client_id | varchar(100) | 可空 |
| source_ip | varchar(64) | |
| result | varchar(16) | `SUCCESS` / `FAILURE` |
| detail | jsonb | 不含密码、令牌原文 |

`type`：`LOGIN_SUCCESS`、`LOGIN_FAILURE`、`ACCOUNT_LOCKED`、`LOGOUT`、`TOKEN_ISSUED`、`TOKEN_REFRESHED`、`TOKEN_REVOKED`、`REFRESH_REUSE_DETECTED`、`USER_CREATED`、`USER_UPDATED`、`PASSWORD_CHANGED`、`CLIENT_CREATED`、`CLIENT_UPDATED`、`SESSION_REVOKED`。应用数据库角色对该表只有 INSERT 与 SELECT 权限。

## 框架表（标准结构）

| 表 | 用途 | 关键约定 |
|---|---|---|
| `oauth2_registered_client` | client 登记 | SAS 的 `JdbcRegisteredClientRepository` |
| `oauth2_authorization` | 授权码、令牌、刷新令牌链 | `principal_name` = 用户 id；额外列 `auth_session_id`（varchar(64)）= 该授权所属的 SSO 登录的 `sid`（HTTP 会话 id 的 SHA-256 哈希，登录轮换会话 id 之后才产生授权，所以整个登录期间保持不变），退出与吊销按它精确删除；`JdbcOAuth2AuthorizationService`，外加重用检测装饰器 |
| `spring_session` / `spring_session_attributes` | SSO 会话 | 空闲 30 分钟、绝对 8 小时（FR-003）；绝对期由自有过滤器按 `creationTime` 校验 |

## 关系

```text
auth_user 1 ── * oauth2_authorization (principal_name)
auth_user 1 ── * spring_session (principal_name)
spring_session 1 ── * auth_sso_client * ── 1 oauth2_registered_client / auth_client_ext
auth_sso_client 1 ── * auth_logout_delivery（按 session_id + client_id）
auth_audit_event → 只追加，不设外键
```

同一类内的关联靠业务键而非外键，便于清理与迁移，与 `main` 上 backend 的约定一致。

## 清理

| 表 | 清理方式 |
|---|---|
| `spring_session`、`spring_session_attributes` | Spring Session JDBC 自带定时清理（`spring.session.jdbc.cleanup-cron`，每 10 分钟） |
| `oauth2_authorization` | 定时任务删除授权码、访问令牌、刷新令牌的过期时间都早于“现在减 1 天”的记录 |
| `auth_sso_client` | 定时任务删除 `session_id` 已不在 `spring_session` 中的行 |
| `auth_logout_delivery` | 定时任务删除 `DELIVERED` 或 `GAVE_UP` 且超过 7 天的行 |
| `auth_audit_event` | 不清理（审计保留期另行决定，本期不做） |

定时任务用 PostgreSQL advisory lock 保证同一时刻只有一个实例执行；保留天数在配置 `auth.cleanup.*` 中。

## 校验规则（来自 spec）

- username 小写后唯一（FR-015）；创建时的密码必须满足策略（FR-017）。
- 回调地址、退出后地址与登记值精确匹配，不做通配（FR-005）。
- 机密 client 的 secret 只在创建与轮换时返回一次，库中只存哈希（FR-024）。
- 修改密码需要当前密码；重置密码由管理员触发并置 `must_change_password`（FR-019a、FR-020）。
