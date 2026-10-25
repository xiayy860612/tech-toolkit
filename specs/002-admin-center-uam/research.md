# Research: Admin Center 用户管理与会话管理

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-23

本文件解决 plan 的 Technical Context 中的全部未知项，每条给出 Decision / Rationale / Alternatives。条目编号 R1…R23 在 plan、data-model、contracts 中被引用。

## 0. 输入与前提

| 来源 | 内容 |
|---|---|
| 用户确认（spec Clarifications） | 不分角色；已登录用户可改自己的密码；写操作只写应用日志；账号与会话一对一；Admin Center 基于会话、UAM 不基于会话 |
| 仓库现状 | `main` 上没有任何应用代码，`docs/achieve-specs/` 与 `docs/system-design/` 尚无内容；constitution 仍是未填写的模板 |
| 仓库先例（仅作技术栈参考，不作依赖） | 本仓库历史分支采用 Spring Boot 4 + Next.js（`sb3-migrate-to-sb4`、`feature/setup-project`、`nextjs-ts-ssr-*`）；未合并的 `001-platform-web-uam` 用 Spring Boot 4 / JDK 21 / PostgreSQL / Redis + Next.js 15 / shadcn |
| **待用户确认的决定** | **R2 技术栈**（沿用仓库先例）。spec 没有指定语言或框架，这是 plan 阶段唯一一处凭先例做的选择 |

## 1. 仓库与服务形态

### R1 仓库结构

- **Decision**: 单仓多应用：`apps/uam`（Spring Boot）与 `apps/admin-center`（Next.js）；共享契约先放本特性 `contracts/uam/openapi.yaml`，第一个实现任务把它迁到仓库根 `contracts/uam/openapi.yaml` 作为唯一权威；本地依赖与多实例编排放 `infra/local/`；压测脚本放 `infra/load/k6/`。各应用目录按根 `AGENTS.md` 带 `AGENTS.md` 与 `CLAUDE.md`。
- **Rationale**: 与根 AGENTS.md 对子项目文档的约定一致；契约独立于任一实现，便于契约测试。
- **Alternatives**: 双仓（增加协作与版本对齐成本，无收益）；契约只放 UAM 内（Admin Center 的生成类型与桩测试要跨目录引用，不利于「契约优先」）。
- **风险**: 未合并的 `001-platform-web-uam` 也有 `apps/uam`。两条线若都要进 `main`，目录会冲突；本特性按用户选择独立于 001，冲突留待合并时处理（见 §8 风险）。

### R2 技术栈（待用户确认）

- **Decision**:
  - UAM：Java 21、Spring Boot 4.0.x（Web MVC、Validation、JDBC、Actuator）、spring-security-crypto + BouncyCastle（Argon2id）、Flyway 社区版、Lettuce（Redis）、Micrometer。
  - Admin Center：TypeScript 5、Node.js LTS、Next.js 15（App Router、SSR、Server Actions）、React 19、Tailwind CSS 4、shadcn/ui（Radix UI）、zod。
  - 存储：PostgreSQL 16（用户、账号、凭据）、Redis 7（会话、限流计数、幂等键）。
- **Rationale**: 与仓库既有技术路线一致；Next.js SSR 天然是「接入层 Web Server」，Spring Boot 天然是 RESTful 领域服务，与 spec 的两个角色一一对应；PostgreSQL 承担强一致的用户 / 账号事务，Redis 承担会话的低延迟读写与原子脚本。
- **Alternatives**: FastAPI 作 UAM（仓库有 `feature/setup-project` 先例，但没有 Argon2 / Redis 脚本的现成既有规范，且用户的历史分支在向 Spring Boot 4 迁移）；会话放 PostgreSQL（读写放大，撤销与过期需轮询）；Admin Center 用纯 SPA（不满足「接入层 Web Server」，cookie 与会话数据的服务端持有无法成立）。

## 2. UAM 与 Admin Center 的接口

### R3 通信与契约

- **Decision**: 内部网络上的 REST / JSON over HTTPS，契约用 OpenAPI 3.1，错误体用 RFC 9457 `application/problem+json`。接口分两组，**两组都不用 cookie、不依赖服务端会话**：
  - `/v1/auth/*`：登录、校验会话、退出。尚无会话数据，靠**服务密钥**识别客户端（R4）。
  - 资源接口 `/v1/users`、`/v1/online-users`、`/v1/sessions`、`/v1/accounts/{accountId}/password`：用户、账号与会话的查询与管理。**不使用任何用户凭据**，由 Admin Center 把操作人上下文作为 `X-Operator-*` 请求头传入（R5）；所有接口都先过服务密钥校验（R4）。
- **路径命名（2026-10-23 用户提出，采纳）**：UAM 是通用领域服务，路径按**资源**命名，**不带客户端名，不分 `/admin`**。`admin` 说的是「谁在用」，不是「是什么资源」；日后业务服务等新客户端会用同一批资源，若把 Admin Center 写进路径，要么新客户端复用一个名不副实的路径，要么再造一套平行路径。修改密码按账号寻址：`POST /v1/accounts/{accountId}/password`，需要当前密码，UAM 不依赖用户凭据。`/v1/auth/*` 保留，因为登录、校验、退出是通用的认证动作，且靠客户端凭据而非 JWT。
- **授权不靠路径前缀**：「谁能调什么」本期不分角色（用户已确认），UAM 一律放行；日后引入角色或范围，由 UAM 内一个授权组件按客户端与操作判断，路径与契约不变。这个组件是本期唯一的授权接缝，实现时必须有测试固定「当前一律放行」的行为，避免日后无意中放宽或收紧。
- **Rationale**: 直接对应 spec FR-008：所有调用先过服务密钥校验（R4）；调用不使用用户凭据，操作人以请求头传入（R5）；会话标识只作为 `verify` / `logout` 的输入。
- **Alternatives**: 每次调用自带可验证的用户身份证明（JWT，原方案，已被用户否决：UAM 不基于用户会话，不使用用户凭据）；保留 `/v1/admin/*`（把客户端写进领域 API，见上）；gRPC（浏览器侧不需要，团队与工具链成本高）。

### R4 客户端凭据：静态服务密钥（本期做，2026-10-23 用户确认）

- **Decision**: UAM 的**每个接口**都要求客户端带**静态服务密钥**：请求头 `Authorization: Bearer <服务密钥>`，由一个过滤器统一校验（`/v1/auth/*` 与资源接口一视同仁）。
  - **客户端清单放配置，不入库**：`UAM_CLIENTS`（环境变量、配置文件或密钥管理注入），每项含 `id`（如 `admin-center`）、`keyHashes`（`sha256:<hex>`，1–2 个）、`permissions`（本期仅 `assert-end-user-context`，即「可代用户断言操作人与来源地址」）。客户端只有一两个，建表是过度设计（YAGNI）；客户端多起来再落库。
  - **密钥**：32 字节密码学安全随机数，base64url，带前缀 `uamk_`，便于识别与扫描。**哈希用 SHA-256 并做常量时间比较，不用 Argon2**：密钥本身高熵，不需要慢哈希，而每个请求都要校验，慢哈希会白白拖慢 UAM。
  - **轮换**：每个客户端同时允许两个有效哈希：加新哈希 → 滚动发布 UAM → Admin Center 换新密钥 → 删旧哈希，全程不停机、无人登出。
  - **权限**：只有带 `assert-end-user-context` 的客户端，才能调用登录、校验会话、退出、修改密码与所有写操作（它们依赖操作人或来源地址）；没有该权限的客户端只能调只读查询，其余返回 403 `client_forbidden`。本期只有 `admin-center` 有。
  - **错误码**：缺失 / 无效 / 已撤销 → 401 `client_unauthenticated`（不透露哪里不对）；权限不够 → 403 `client_forbidden`。与用户级错误码（如 `session_invalid`）分开，使 Admin Center 能区分「用户会话无效」与「客户端配置错误」。
  - **工具**：UAM 的一次性子命令 `generate-service-key --client <名称>`（不连库、不连 Redis）：标准输出只输出密钥一次，标准错误打印对应哈希与 `UAM_CLIENTS` 配置片段。
  - **Admin Center**：环境变量 `UAM_SERVICE_KEY`（来自密钥存储），缺失即启动失败；每个对 UAM 的请求都带它；收到 401 `client_unauthenticated` / 403 `client_forbidden` 时按 `unverified` 处理（不登出、不清 cookie、提示服务暂不可用，并写告警日志）。
  - **拒绝日志**：被拒绝的调用写结构化日志（来源地址、时间、原因类别，**不含密钥**）。
- **Rationale**: 用户选择静态服务密钥并要求纳入本期。没有用户凭据的 UAM 需要先知道「谁在调」，否则能连上 UAM 的任何人都能冒充运维人员。它最简单、哪里都能跑，也不妨碍以后升级。
- **Alternatives**: mTLS / 服务网格身份、平台原生身份（Kubernetes 服务账号令牌、SPIFFE）、OAuth2 客户端凭据、请求签名（HMAC）：本期都更重或依赖基础设施，后期再选。
- **接缝**: 「识别客户端」收在一个组件里（输入请求，输出客户端 id 与权限）。将来换成传输层身份或平台身份，只换它，接口、操作人请求头与业务代码都不动。
- **后果与残余风险**: ① 服务密钥泄露等于客户端被冒充（持密钥者可填任意操作人），请把它当作与数据库密码同级的机密并定期轮换；② 网络边界仍然要求（UAM 只对客户端开放），两者是两层保护；③ 密钥是静态的，抗重放能力弱，靠 TLS 与内网保护。

### R5 会话数据与操作人上下文：不用 JWT（2026-10-23 用户确认）

- **Decision**: **不使用 JWT，也不使用任何用户凭据访问 UAM**。UAM 不基于用户会话，只识别调用它的服务（服务密钥，R4）。
  - `login` 与 `verify` 成功时返回**普通的会话数据**：`user{id,name}`、`account{id,loginName,kind}`、`sessionRef`、`expiresAt`（`login` 另返回 `sessionId` 与 `maxAgeSeconds`）。没有签名、没有 `kid`、没有有效期。
  - Admin Center 对每个浏览器请求先 `verify`，得到会话数据后，把**操作人上下文**作为请求头传给 UAM：`X-Operator-User-Id`、`X-Operator-Account-Id`、`X-Operator-Session-Ref`（均为 UUID）。写操作（新建、停用、启用、强制下线、改密）必须带，缺失返回 400 `operator_required`；读操作可选（`GET /v1/users/{id}/sessions` 用会话引用算 `current`）。
  - UAM **信任**客户端传来的操作人，只用它做两件事：自我保护规则（不能停用自己、不能强制下线自己的会话、标注 `current`）与写操作日志（R16）。它不是认证，也不用于授权。
  - 会话数据与操作人上下文**只在 Admin Center 服务端与 UAM 之间**流转：不写 cookie、不返回浏览器、不写日志（会话引用除外，它是非机密的）。
- **Rationale**: UAM 是通用领域服务，不基于用户会话，因此不使用用户凭据（用户确认）；JWT 本质上就是用户凭据，放进去会把「用户会话」带进 UAM 的访问方式。去掉后没有签名密钥、轮换、有效期窗口这些东西，UAM 与用户会话彻底解耦，将来多个客户端服务也只需服务凭据。
- **Alternatives**: JWT（ES256 / HS256，原方案）；Admin Center 把会话标识带给 UAM、每次由 UAM 查 Redis（UAM 变成基于会话）。
- **后果**: ① 持有服务密钥且被授予断言权限的客户端可以填写任意操作人，所以服务密钥与网络边界（R4、R23）共同保护；② UAM 不再对写操作校验操作人的会话是否存活，会话是否有效由客户端（Admin Center）每个请求 `verify` 保证；③ 参考图二里的 JWT 在本特性不作为 UAM 的访问凭据。

### R6 撤销与时效

- **Decision**: 只有**一层**：浏览器路径。Admin Center 对每个浏览器请求 `verify` 会话标识；会话结束（退出、强制下线、停用、到期、被取代）后，经任一实例的下一次请求必判未登录，窗口为 **0**（FR-011、SC-013）。UAM 的资源接口不使用用户凭据，所以没有「已签发的凭据在会话结束后仍被接受」的窗口。
- **Rationale**: 去掉 JWT 后，撤销的唯一入口就是 `verify`，行为简单、可测。
- **残余风险**: 一个刚被强制下线的运维人员，其请求若已经通过 `verify` 并正在执行，那次写操作仍会完成（Admin Center 单个请求内的毫秒级窗口）；可接受。

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

## 4. 认证与安全

### R10 密码、强度与登录失败限制

- **Decision**: Spring Security `DelegatingPasswordEncoder`，默认 Argon2id，登录成功且参数落后时自动升级。强度策略默认：至少 10 位，同时含字母与数字（可配置）。登录失败按 `SHA-256(规范化账号名 | 来源地址)` 计数，连续 5 次后拒绝该来源 15 分钟（期间即使凭据正确也拒绝，返回 429 + `Retry-After`）；账号不存在时同样计数，并执行一次伪哈希比对使耗时与「密码错误」无差别。**无论账号不存在、密码错误、账号停用还是用户停用，都先做哈希比对，再统一返回 `invalid_credentials`**（FR-005）。
- **Rationale**: 防枚举、防撞库，且停用状态不泄露给未认证者。

### R11 请求来源地址

- **Decision**: Admin Center 从入口反向代理设置的可信头中，按配置的「可信跳数」取客户端地址，放进登录与改密请求体的 `clientIp`；只有被授予断言权限的客户端（经服务密钥识别，R4）才可提交该字段，UAM 信任它。用户自带的 `X-Forwarded-For` 不被直接采信。
- **Rationale**: 失败限制的来源维度必须不可伪造。

### R12 Cookie 策略

- **Decision**: 名称默认 `__Host-asid`（开发环境可配置为不带前缀）；属性 `HttpOnly; Secure; SameSite=Strict; Path=/`，不设 `Domain`；`Max-Age` 为剩余绝对寿命。
- **Rationale**: 满足 FR-003 的三项属性，并防止同站子域覆盖。

### R13 CSRF 与写操作入口

- **Decision**: 所有写入口（登录、退出、新建用户、停用、启用、强制下线、改密）都是 POST（Next.js Server Actions 或 Route Handler），Admin Center 对非 GET 请求校验 `Origin` / `Sec-Fetch-Site` 同源；`SameSite=Strict` 作第二层。登录表单同样校验同源以防登录 CSRF。UAM 一侧不处理 cookie，因此不存在 CSRF 面。
- **Rationale**: 满足 FR-033（跨站伪造的写请求 100% 被拒绝，SC-014）。

### R14 Admin Center 的会话三态与 UAM 不可用

- **Decision**: 服务端 `getSession()`：读 cookie → 调 `verify`（超时默认 1.5 秒）→ 三态：`authenticated` / `anonymous`（无 cookie，或 UAM 明确 401）/ `unverified`（超时、5xx、连接失败）。`anonymous` 且曾带 cookie 时经 `/session/expire` 清 cookie（页面渲染期间不能写 cookie）；`unverified` 时受保护页面显示「服务暂不可用」并**保留 cookie**；写操作在 UAM 不可用时提示「操作未生效」，绝不显示成功。每个请求内用 React `cache` 去重，不在 middleware 里发网络请求。
- **Rationale**: 对应 spec 的 FR-009 与 UAM 不可用边界情况。

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

## 6. 数据访问、可观测性与扩展

### R17 数据访问与迁移

- **Decision**: PostgreSQL + Flyway **社区版**（只前进，出错时新写修复迁移）；数据访问用 Spring `JdbcClient` 与显式 SQL，仓储接口在领域层、实现在基础设施层。用户搜索「大小写不敏感、包含匹配」用 `pg_trgm` GIN 索引支持 `ILIKE '%q%'`（用户名、账号名规范化列各一个索引）；`accountCount` 用 `account(user_id)` 索引聚合；分页用 `limit/offset`（10 万用户下可接受，深分页极少）。
- **Rationale**: 10 万用户下的包含匹配若无三元组索引会全表扫描；显式 SQL 便于审查与测试。

### R18 可观测性与横向扩展

- **Decision**: 两个服务都无实例本地状态：UAM 的状态在 PostgreSQL / Redis，Admin Center 无存储；任一实例可处理任一请求，**负载均衡无需会话粘滞**。结构化 JSON 日志；Micrometer 指标；服务间传递 W3C `traceparent`；存活 / 就绪探针（UAM 就绪检查依赖 Redis 与 PostgreSQL）；优雅停机以满足 SC-012（滚动重启 0 次意外登出）。本地多实例编排：`infra/local/docker-compose.scale.yml` + nginx。
- **Rationale**: 直接对应 FR-037 ~ FR-040 与 SC-010 ~ SC-012。

## 7. 界面、测试与原型

### R19 界面与自适应

- **Decision**: 「顶栏 + 左侧边栏 + 内容区」：桌面侧边栏展开、平板收为图标条、手机为抽屉；用 shadcn/ui `Sidebar`（`collapsible="icon"`）、`Sheet`、`Table`、`Card`、`Badge`、`Dialog` / `AlertDialog`（确认框，自带焦点限制与 `Esc` 关闭）、`DropdownMenu`（顶栏用户菜单）、`Breadcrumb`。列表在手机上变为卡片堆叠（`md` 以下切换）。表单用原生 `<form>` + Server Actions + `useActionState`，服务端 zod 校验；不引入 react-hook-form（YAGNI）。
- **Rationale**: 满足 FR-035 / FR-036；使用成熟的可访问组件以满足键盘与焦点要求。

### R20 测试与覆盖率（根 AGENTS.md「测试先行」与 ≥ 90% 门禁）

- **Decision**: 每个任务先写会失败的测试（red → green → refactor）。
  - UAM：JUnit 5 + AssertJ + Mockito 单元测试；Testcontainers（PostgreSQL、Redis）集成测试；基于 OpenAPI 的契约测试；ArchUnit 分层测试；**并发测试**（并发登录同账号、并发建同名用户、并发停用最后两个用户、停用与登录交错）；日志脱敏测试；客户端认证测试（缺失 / 错误 / 已撤销密钥 → 401，无断言权限 → 403，轮换期两把密钥同时有效，密钥不进日志）。JaCoCo 行覆盖率 ≥ 90%，排除路径仅限启动类、纯配置类、生成代码。
  - Admin Center：Jest + React Testing Library；msw 的 UAM 桩按 OpenAPI 校验请求与响应；Playwright E2E 与视口矩阵（320 / 375 / 768 / 1024 / 1440 / 2560）；`@axe-core/playwright`。覆盖率 ≥ 90%，排除 `src/components/ui/**`、生成代码、`*.d.ts`。
  - 扩展与弹性：k6（SC-009、SC-011）；滚动重启脚本（SC-012）。
- **Rationale**: 契约变更须先改契约与失败用例（根 AGENTS.md）；并发规则是本特性最容易出错的部分，必须有确定性的并发测试。

### R21 界面设计原型的形态

- **Decision**: 原型是**零依赖的静态页面**（`prototype/`，HTML + CSS + 少量 JS，内存假数据），用任何浏览器直接打开：`index.html` 是查看器（顶部有桌面 / 平板 / 手机视口切换与场景选择，把 `app.html` 放进对应宽度的框里），`app.html` 是真实的响应式界面（哈希路由，可点击流转）。场景覆盖 spec FR-041 列出的全部页面与状态。原型**不是**生产代码，不被实现复用；它与 spec 的对应关系见 `prototype/README.md`。原型须经用户确认后才开始界面实现。
- **Rationale**: 评审者无需安装环境（FR-041 / US11）；把「视口」与「状态」做成显式开关，评审时不必手工构造数据。
- **Alternatives**: Figma（需要账号，不在仓库内）；用生产框架搭原型（评审者需要运行环境，且容易被误当成实现）。

## 8. 部署、初始化与风险

### R22 部署期初始化（首批数据）

- **Decision**: UAM 的同一镜像 / jar 提供两个一次性子命令（不是运维入口，各自幂等、`cli` profile、不连 Redis、先跑 Flyway、日志写标准错误）：
  - `init-admin`：在没有任何用户时创建首个用户与账号（密码只经标准输入，按强度策略校验）。
  - `generate-service-key`：生成服务密钥与其哈希（见 R4）；不连库、不连 Redis。
  - `add-account`：给**已有用户**增加第二个及以后的「账号名 / 密码」账号（`--user-id` 或 `--account-name-of`，密码只经标准输入）。界面上新建的用户恰有一个账号（spec Assumptions），多账号用户只能由它产生。
- **Rationale**: spec 要求「首批数据由部署期预置」且「多账号只能经部署期预置」；用命令行工具避免把密码写进脚本或迁移文件。

### R23 网络边界（2026-10-23 用户确认）

- **Decision**: **UAM 只在内网访问**：UAM 不对公网、浏览器或第三方暴露；会话数据与操作人上下文只在 Admin Center 服务端与 UAM 之间的内网链路上流转。UAM 仅接受 Admin Center 实例的连接（与服务密钥一起构成两层保护，见 R4），PostgreSQL 与 Redis 仅接受 UAM 的连接。这是部署配置，应用不实现 IP 白名单，也不因内网而放松应用层安全（登录失败限制、密码哈希与写操作规则照常；服务密钥见 R4）。
- **与已有设计的关系**：与 R5 一致：会话数据与操作人上下文不进 cookie / 浏览器 / 日志；没有任何用户凭据，也没有 JWKS 端点。
- **传输**：内网内的 Admin Center → UAM 链路仍用 TLS（登录请求体含密码，响应含会话标识）；TLS 可在服务入口或服务网格终止。若希望内网内用明文 HTTP，需另行确认，因为这会让密码、会话标识与 JWT 在内网里以明文传输。

### 风险与待办

| 风险 | 影响 | 处置 |
|---|---|---|
| 技术栈沿用先例，未经用户确认 | 若用户要别的栈，plan 需重做 | 在完成报告中显式请用户确认（R2） |
| `001-platform-web-uam` 与本特性都用 `apps/uam` | 两条线都合入 `main` 时目录冲突 | 合并前协商；本特性已编号 002 避免规格目录冲突 |
| 在线用户聚合为 O(在线会话数) | 超过验收规模的 10 倍后变慢 | 记录扩展方案（按用户分片的 ZSET）；SC-009 在 k6 中验证 |
| Redis 主从切换可能丢失最后约 1 秒的撤销 | 极端情况下一个已被强制下线的会话短暂复活 | 已接受（R7）；恢复后 `verify` 立即按 Redis 的真实状态判定 |
| 不分角色 + 只有应用日志 | 任一登录用户可停用他人、日志不防篡改 | 用户已确认（Clarifications）；日后引入权限与审计时再收紧 |
| 服务密钥泄露或配置错误 | 持密钥者可冒充 Admin Center（填任意操作人）；配置错误会让 Admin Center 全面失败 | 密钥只放密钥存储、定期轮换、网络边界只对客户端开放；配置错误按「服务暂不可用」处理（不登出用户）并告警；日后换传输层身份（R4） |
| 会话标识不随改密轮换 | 若日后放宽为多会话需重新评估 | 记录为前提（R15） |

## 9. 需要回写 spec 的对齐点

无。spec 的两条 plan 级留白（撤销时效、客户端身份）已由 R6 与 R4 解答（静态服务密钥本期做，用户确认），不改变任何已确认的行为。
