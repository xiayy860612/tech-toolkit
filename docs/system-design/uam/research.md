# Research（UAM）: Admin Center 用户管理与会话管理

> **过程记录（UAM）**：原文为 `specs/002-admin-center-uam/research.md`，归档时按 app 拆分：跨服务决策在 [system](../system/research.md)（R1–R6、R11、R18、R20、R23、风险）、UAM 在 [uam](./research.md)（R7–R10、R15–R17、R22）、Admin Center 在 [admin-center](../admin-center/research.md)（R12–R14、R19、R21）；条目编号 R1…R23 全局不变。当前真实状态以各目录 `README.md` 与代码为准。

**Spec**: [spec.md](../../achieve-specs/admin-center/spec.md) | **Plan**: [plan.md](./plan.md) | **数据模型**: [data-model.md](./data-model.md)

## 3. 会话模型与存储

### R7 会话标识、会话引用与单会话

- **Decision**: 会话标识为 256 位随机数（密码学安全，base64url），Redis 只存 `SHA-256(会话标识)`；每个会话另有随机 UUID 作为**会话引用**，是页面与接口里唯一的会话名称。**账号与会话一一对应**由 Lua 脚本原子保证：登录时一次完成「读旧 → 删旧 → 写新 → 更新索引」。结构见 [data-model.md](./data-model.md)。Redis 单主 + 高可用（Sentinel 或托管），**不使用 Redis Cluster**；撤销类写入后 `WAIT 1 <短超时>`；开启 AOF `everysec`。
- **Rationale**: 满足 FR-004 / FR-011 / FR-031；Lua 保证并发登录同一账号只留一个会话；单主读取避免读到副本的旧状态。
- **Alternatives**: PostgreSQL 存会话（并发登录需要行锁，过期要定时清理）；Cluster（Lua 多键脚本需要同槽，复杂度高，10 万量级用不上）。
- **接受的残余风险**: Redis 主从切换瞬间，最后约 1 秒内尚未复制的撤销可能丢失。

### R8 过期与滑动续期

- **Decision**: 空闲 30 分钟、存续最长 12 小时（spec 默认，均可配置）。Redis TTL 取两者剩余时间的较小值。`verify` 时若距上次写入超过 60 秒才更新 `lastActiveAt` 并刷新 TTL，避免每个页面请求都产生一次写；由此有效空闲时间在 29–30 分钟之间。cookie 的 `Max-Age` 取剩余绝对寿命，不早于会话有效期（服务端为权威）。
- **Rationale**: 降低写放大，对运维场景影响可忽略。

### R9 在线用户列表的索引

- **Decision**: 维护 Redis 有序集合 `uam:online`，成员为 `{userId}|{sessionRef}`，分数为该会话的空闲到期时间（取空闲与绝对到期的较小者，epoch 秒）。登录、续期、终止、被取代时在同一个 Lua 脚本里同步更新。列表接口先 `ZREMRANGEBYSCORE -inf now` 清理，再取全部有效成员在 UAM 内按用户聚合（会话数、最近活动 = 最大分数 − 空闲时长），分页后回 PostgreSQL 批量取用户名。某用户的会话明细不扫集合，而是取该用户的全部账号，逐个读 `uam:acct:{accountId}:sess`（一对一，账号数极少）。
- **Rationale**: 1 万个会话的聚合是毫秒级，且结果总与会话存在性一致（SC-003），不需要额外的用户级计数器去维护；写路径只增加一个 ZADD / ZREM。
- **Alternatives**: 每用户一个集合 + 用户级 ZSET（要在过期时修剪，计数容易漂移）；在 PostgreSQL 建会话镜像表（双写一致性问题）。
- **扩展边界**: 复杂度为 O(在线会话数)。验收规模 1 万；到 10 倍以上时改为按用户分片的 ZSET，留作后续优化（见 §8）。

## 4. 认证与安全（UAM 部分）

### R10 密码、强度与登录失败限制

- **Decision**: Spring Security `DelegatingPasswordEncoder`，默认 Argon2id，登录成功且参数落后时自动升级。强度策略默认：至少 10 位，同时含字母与数字（可配置）。登录失败按 `SHA-256(规范化账号名 | 来源地址)` 计数，连续 5 次后拒绝该来源 15 分钟（期间即使凭据正确也拒绝，返回 429 + `Retry-After`）；账号不存在时同样计数，并执行一次伪哈希比对使耗时与「密码错误」无差别。**无论账号不存在、密码错误、账号停用还是用户停用，都先做哈希比对，再统一返回 `invalid_credentials`**（FR-005）。
- **Rationale**: 防枚举、防撞库，且停用状态不泄露给未认证者。

## 5. 写操作规则（UAM 内）

### R15 新建、停用、启用、强制下线、改密

- **新建用户**：单个 PostgreSQL 事务写 `app_user` + `account` + `credential`；账号名规范化列上的唯一索引保证全局唯一（含已停用用户的账号），冲突返回 409 `account_name_taken`。接受 `Idempotency-Key`：Redis 保存「键 → 响应」10 分钟，同键重放返回原 201，保证双击与网络重试不产生两个用户（FR-022）；并发抢同一账号名只有一个成功。
- **停用 / 启用**：在事务内取 `pg_advisory_xact_lock` 串行化「用户状态变更」，校验「不能停用自己」「不能停用最后一个正常用户」（并发下也不会出现零个正常用户，FR-025）；**提交后**再终止该用户全部账号的会话。登录流程在写入会话之后**再读一次用户状态**，若已停用则立即终止刚建的会话并拒绝，这样「停用与登录并发」两种交错顺序都不会留下已停用用户的有效会话。重复操作幂等。启用不恢复会话。
- **强制下线**：按会话引用终止（`uam:sref:{ref}` → 哈希 → Lua 一次清理四处索引）；目标等于请求头 `X-Operator-Session-Ref` 时返回 409 `cannot_terminate_own_session`；目标不存在返回 404 `session_not_found`，界面显示「该会话已结束」。只终止会话，不改变账号与用户状态。
- **改密**：校验当前密码（失败计数键 `SHA-256(accountId | clientIp)`，口径同登录）、新密码强度且不同于当前、写 `credential`；**只改路径里的 `accountId` 对应的账号**（Admin Center 传入操作人自己的账号；UAM 以「当前密码正确」保护它，不依赖用户凭据）；当前会话保持有效。**不轮换会话标识**：因账号与会话一一对应，被改密的账号不存在需要另行处理的其他会话，且会话标识由服务端在登录时生成，没有固定化风险；若日后改为多会话，须重新评估。
- **Rationale**: 把规则集中在 UAM，Admin Center 不依赖也不重复实现（FR-015）；锁与交错顺序的设计使并发场景可测试。
- **Alternatives**: 乐观锁重试（最后一个正常用户的并发保护需要全局视角，咨询锁更直接）；改密后轮换标识（对一对一会话无额外收益，增加 cookie 更新与失败回滚复杂度）。

### R16 写操作应用日志（FR-034）

- **Decision**: 每次写操作由 UAM 写一条结构化 JSON 日志，事件名 `uam.admin.write`，字段：`ts`、`operatorUserId`、`operatorAccountId`、`clientId`、`action`（`user.create` / `user.disable` / `user.enable` / `session.terminate` / `account.password.change`）、`targetType`、`targetId`、`outcome`（`success` / `rejected` / `failed`）、`reason`（原因类别，如 `cannot_disable_self`）、`traceId`。被拒绝的操作（未登录、规则不允许、校验失败）也记录。统一的脱敏层保证密码、哈希、会话标识、会话数据永不进入日志；用日志捕获测试断言。不提供界面，不要求不可篡改存储。
- **Rationale**: 用户选择 B；一条结构化日志即可在不新增存储与页面的前提下提供追溯。
- **Alternatives**: 数据库审计表 + 查看页（用户选择 C 之外的方案，范围大）；完全不记（用户已否决）。

## 6. 数据访问

### R17 数据访问与迁移

- **Decision**: PostgreSQL + Flyway **社区版**（只前进，出错时新写修复迁移）；数据访问用 Spring `JdbcClient` 与显式 SQL，仓储接口在领域层、实现在基础设施层。用户搜索「大小写不敏感、包含匹配」用 `pg_trgm` GIN 索引支持 `ILIKE '%q%'`（用户名、账号名规范化列各一个索引）；`accountCount` 用 `account(user_id)` 索引聚合；分页用 `limit/offset`（10 万用户下可接受，深分页极少）。
- **Rationale**: 10 万用户下的包含匹配若无三元组索引会全表扫描；显式 SQL 便于审查与测试。
## 8. 部署期初始化

### R22 部署期初始化（首批数据）

- **Decision**: UAM 的同一镜像 / jar 提供两个一次性子命令（不是运维入口，各自幂等、`cli` profile、不连 Redis、先跑 Flyway、日志写标准错误）：
  - `init-admin`：在没有任何用户时创建首个用户与账号（密码只经标准输入，按强度策略校验）。
  - `generate-service-key`：生成服务密钥与其哈希（见 R4）；不连库、不连 Redis。
  - `add-account`：给**已有用户**增加第二个及以后的「账号名 / 密码」账号（`--user-id` 或 `--account-name-of`，密码只经标准输入）。界面上新建的用户恰有一个账号（spec Assumptions），多账号用户只能由它产生。
- **Rationale**: spec 要求「首批数据由部署期预置」且「多账号只能经部署期预置」；用命令行工具避免把密码写进脚本或迁移文件。
