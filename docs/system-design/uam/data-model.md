# Data Model: Admin Center 用户管理与会话管理

**Feature**: [spec.md](../../achieve-specs/admin-center/spec.md) | **Research**: [research.md](../system/research.md) | **Date**: 2026-10-23

权威状态存放：**PostgreSQL**（用户、账号、凭据，强一致）与 **Redis**（会话、限流计数、幂等键，低延迟）。Admin Center 不持久化任何数据。表结构以日后的 Flyway 迁移为准，本文件是设计依据。

## 1. 实体与关系

```text
app_user 1 ──── * account 1 ──── 1 credential        （用户 : 账号 = 1 : N；密码账号恰有一条凭据）
                    │ 1
                    └──── 0..1 session  [Redis]       （账号 : 会话 = 1 : 1，对应 spec 参考图一）
session ──(经 account)──> app_user                    （会话通过账号隶属于唯一的用户）
```

## 2. PostgreSQL

### 2.1 `app_user`（用户）

| 列 | 类型 | 约束 / 说明 |
|---|---|---|
| `id` | `uuid` | 主键 |
| `name` | `varchar(64)` | 非空；去首尾空白后 1–64 字符；**不要求唯一** |
| `status` | `text` | 非空；`ACTIVE` / `DISABLED`（`CHECK`） |
| `created_at` | `timestamptz` | 非空，默认 `now()` |
| `updated_at` | `timestamptz` | 非空 |

索引：`name` 上 `pg_trgm` GIN（包含匹配）；`status` 上普通索引（统计正常用户数）。

### 2.2 `account`（账号）

| 列 | 类型 | 约束 / 说明 |
|---|---|---|
| `id` | `uuid` | 主键 |
| `user_id` | `uuid` | 非空，外键 → `app_user.id`；索引 |
| `kind` | `text` | 非空；本期仅 `PASSWORD`（`CHECK`，日后扩展时放宽） |
| `login_name` | `varchar(32)` | 非空；创建时的原样账号名，**创建后不可改** |
| `login_name_norm` | `varchar(32)` | 非空；`lower(login_name)`；**全局唯一索引**（含已停用用户的账号）；`pg_trgm` GIN（搜索） |
| `status` | `text` | 非空；`ACTIVE` / `DISABLED` / `DELETED`。`DELETED` 为预留（逻辑删除，名称仍被占用），本期没有任何接口或界面会写入它，只在登录判定中按「非 `ACTIVE` 即不可登录」处理（会话校验只读 Redis，不回查账号状态，见 research R6） |
| `created_at` | `timestamptz` | 非空，默认 `now()` |

**账号能否登录** = `account.status = ACTIVE` **且** `app_user.status = ACTIVE`。启用用户不改变账号自身状态（spec Assumptions）。

### 2.3 `credential`（凭据，仅「账号名 / 密码」账号）

| 列 | 类型 | 约束 / 说明 |
|---|---|---|
| `account_id` | `uuid` | 主键，外键 → `account.id` |
| `password_hash` | `text` | 非空；Argon2id（`DelegatingPasswordEncoder` 格式，可升级）；明文永不落库、不进日志 |
| `updated_at` | `timestamptz` | 非空 |

### 2.4 客户端与服务密钥（配置，不入库）

客户端清单放 UAM 的配置（环境变量、配置文件或密钥管理注入），不建表（research R4）：

| 字段 | 说明 |
|---|---|
| `id` | 客户端名称，如 `admin-center`；写操作日志里的 `clientId` |
| `keyHashes` | 1–2 个 `sha256:<hex>`，对应高熵服务密钥；两个同时有效用于不停机轮换 |
| `permissions` | 本期仅 `assert-end-user-context`：可代用户断言操作人与来源地址 |

密钥本身（32 字节随机数，带 `uamk_` 前缀）只在 `generate-service-key` 输出一次，由客户端放进自己的密钥存储；UAM 只持有哈希。

## 3. Redis

所有键带 `uam:` 前缀；会话类键的 TTL 取「空闲剩余」与「绝对剩余」的较小值。**会话标识明文从不入库**，只存 `SHA-256`。

| 键 | 类型 | 内容 | 说明 |
|---|---|---|---|
| `uam:sess:{sha}` | HASH | `ref`、`accountId`、`userId`、`loginAt`、`lastActiveAt`、`absExpiresAt` | `{sha}` = SHA-256(会话标识)；`verify` 的查找键 |
| `uam:acct:{accountId}:sess` | STRING | 当前有效会话的 `{sha}` | **一对一**：每账号至多一个；登录脚本据此顶掉旧会话 |
| `uam:sref:{ref}` | STRING | 对应的 `{sha}` | 按会话引用终止、自检（对照 `X-Operator-Session-Ref`）用 |
| `uam:online` | ZSET | 成员 `{userId}\|{ref}`，分数 = 空闲到期（epoch 秒，取空闲与绝对到期较小者） | 在线用户列表的索引（research R9） |
| `uam:term:{sha}` | STRING | 常量标记（不含任何会话数据） | **强制下线墓碑**：仅管理员强制下线（`DELETE /v1/sessions/{ref}`）与会话删除在同一 Lua 脚本里原子写入；TTL = `uam.session.terminated-notice-ttl`（默认 10 分钟）；`verify` 在会话不存在时按所呈现标识的哈希查它，命中则 401 `session_invalid` 带 `reason: terminated`；退出、停用、被取代、到期**不写** |
| `uam:throttle:login:{sha256(规范化账号名\|来源地址)}` | STRING（计数） | 连续失败次数 | 5 次后锁该来源 15 分钟（`Retry-After` 取剩余 TTL） |
| `uam:throttle:pwchg:{sha256(accountId\|来源地址)}` | STRING（计数） | 当前密码连续错误次数 | 口径同登录 |
| `uam:idem:{sha256(客户端id\|键)}` | STRING | 创建用户的响应快照（`D\|指纹\|快照`），或进行中标记（`P\|指纹`） | 快照 TTL 10 分钟、进行中标记 30 秒；`SET NX` 抢占，重放返回原响应；按客户端隔离；快照与指纹都不含密码 |

### 3.1 会话生命周期（Lua 脚本原子完成）

| 事件 | 动作 |
|---|---|
| **登录成功** | 读 `uam:acct:{id}:sess` → 若有旧会话，删其 `sess` / `sref` / `online` 条目 → 写新 `sess`、`acct`、`sref`、`online` → 返回新会话 |
| **校验（verify）** | 读 `sess`；不存在或已过期 → 无效（再查 `uam:term:{sha}`，命中则 401 的 `reason` 为 `terminated`，否则不带 `reason`）；距 `lastActiveAt` > 60 秒才更新 `lastActiveAt`、刷新 TTL 与 `online` 分数 |
| **退出 / 强制下线 / 停用 / 被取代** | 删除 `sess`、`sref`，`acct` 若仍指向该会话则删除，并 `ZREM online`；撤销类写入后 `WAIT 1 <短超时>`。**仅管理员强制下线**在同一脚本里额外 `SET uam:term:{sha} 1 EX <通知窗口>`（墓碑）；退出、停用、登录取代旧会话、登录与停用竞态的回滚都不写墓碑 |
| **过期** | Redis TTL 删除 `sess` / `acct` / `sref`；`online` 条目靠列表接口的 `ZREMRANGEBYSCORE` 与到期分数保证不被计入 |

## 4. 状态转换

### 用户 `app_user.status`

```text
ACTIVE ──停用──► DISABLED ──启用──► ACTIVE
```

- 停用：校验「不是当前登录运维人员所属用户」「不是最后一个 `ACTIVE` 用户」→ 提交事务 → 终止其全部账号的会话。启用：不恢复会话。重复操作幂等。
- 并发：状态变更在 `pg_advisory_xact_lock` 下串行；登录在写入会话后再读一次用户状态，若已 `DISABLED` 则终止刚建的会话并拒绝（research R15）。

### 账号 `account.status`

`ACTIVE ⇄ DISABLED`、`ACTIVE → DELETED` 由 UAM 内部能力决定；本期没有对应的接口或界面（spec：不含单个账号的停用 / 启用与删除），数据模型与登录判定已预留；账号级变更日后若加入，须同时定义它如何终止该账号的会话。

### 会话

```text
（不存在）──登录成功──► 有效 ──退出 / 强制下线 / 停用 / 被取代 / 空闲到期 / 绝对到期──► 结束
```

结束是终态，没有「恢复」；再次使用须重新登录。

## 5. 校验规则

| 项 | 规则 | 来源 |
|---|---|---|
| 用户名 | 去首尾空白后 1–64 字符，不要求唯一 | FR-022 |
| 账号名 | 3–32 个字母、数字或 `_` `.` `-`；大小写不敏感；全局唯一（含已停用用户的账号）；创建后不可改 | FR-022 |
| 密码 | 默认至少 10 位且同时含字母与数字（可配置）；改密时还须不同于当前密码 | FR-022、FR-027 |
| 登录失败 | 同一「账号 + 来源地址」连续 5 次后拒绝该来源 15 分钟，不锁账号 | FR-006 |
| 会话 | 一账号至多一个有效会话；空闲 30 分钟、绝对 12 小时（可配置） | FR-004、FR-010 |
| 操作人上下文 | 写操作必须带 `X-Operator-User-Id` / `-Account-Id` / `-Session-Ref`，缺失返回 400 `operator_required`；UAM 信任客户端传来的值，只用于自我保护规则与日志，不用于认证或授权 | research R5 |
| 服务密钥 | 每个调用都带；SHA-256 哈希常量时间比较；缺失 / 无效 / 已撤销 → 401 `client_unauthenticated`，无断言权限 → 403 `client_forbidden`；至多两把同时有效 | FR-042 ~ FR-044、research R4 |
| 创建的幂等 | `Idempotency-Key` 同键 10 分钟内重放返回原结果；账号名唯一索引兜底 | FR-022 |
| 敏感信息 | 密码、哈希、会话标识、服务密钥永不出现在页面、响应体（登录响应中的会话标识只给持有服务密钥的 Admin Center）与日志 | FR-031、SC-014 |

## 6. 视图（接口返回的聚合，非存储）

| 视图 | 组成 |
|---|---|
| 用户列表项 | `app_user` + 该用户 `account` 数量 |
| 用户详情 | `app_user` + 其全部 `account`（账号名、种类、状态、创建时间） |
| 在线用户项 | 按 `userId` 聚合 `uam:online`：有效会话数、最近活动；用户名回 PostgreSQL 批量取 |
| 用户会话项 | 该用户每个账号的 `acct` 指向的 `sess`：会话引用、账号名与种类（回 PostgreSQL）、登录 / 最近活动 / 到期时间、`current`（操作人会话引用与该会话引用相等） |
