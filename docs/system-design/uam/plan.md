# Implementation Plan（UAM）: Admin Center 用户管理与会话管理

> **过程记录（UAM）**：原文为 `specs/002-admin-center-uam/plan.md`，归档时按 app 拆为 [system](../system/plan.md)（跨服务）、[uam](./plan.md)、[admin-center](../admin-center/plan.md) 三份。当前真实状态以各目录的 `README.md` 与代码为准；偏差清单见 [traceability §9](../system/traceability.md)。

**Spec**: [spec.md](../../achieve-specs/admin-center/spec.md) | **系统级计划**: [../system/plan.md](../system/plan.md) | **数据模型**: [data-model.md](./data-model.md)

## Design

PlantUML 源文件与 SVG 在 [`diagrams/`](./diagrams/)；改图后重新渲染：`java -jar plantuml.jar -tsvg -charset UTF-8 docs/system-design/*/diagrams/*.puml`。

### L3 组件

#### UAM（L3 组件）

![L3 UAM 组件](./diagrams/c4-l3-uam-component.svg)

源文件：[c4-l3-uam-component.puml](./diagrams/c4-l3-uam-component.puml)

分层依赖是 `api → application → domain ← infrastructure`（依赖倒置：用例只依赖 domain 里的端口，基础设施实现这些端口；由 ArchUnit 测试守住）。

| 层 | 组件 | 职责 |
|---|---|---|
| api | `ClientAuthFilter` | 校验服务密钥与客户端权限（401 `client_unauthenticated` / 403 `client_forbidden`） |
| api | `OperatorContextResolver` | 解析 `X-Operator-*`；写操作缺失 → 400 `operator_required` |
| api | `ProblemAdvice` · `ProblemCatalog` | 统一 `problem+json` 错误体；**一个错误码对应一个状态码**：`validation_failed` 一律 422（含请求体 Bean Validation 失败，带 `errors[]`），无法解析 / 类型不符的请求 → 400 `bad_request`（不回显请求值），`operator_required` 仍为 400 |
| api | `AuthController` · `UsersController` · `SessionsController` · `AccountsController` | 对应 OpenAPI 的四组接口，只做协议转换，不含业务规则 |
| application | 认证 · 用户 · 会话 · 改密用例，以及 `AccountProvisioningService`（创建用户 / 追加账号，供「新建用户」用例与部署期命令共用） | 编排领域对象与端口；事务边界；写操作日志 |
| domain | 领域模型与规则 | 见下面的「L3 领域模型」；纯 Java，不依赖框架 |
| infrastructure | JDBC 仓储 · `RedisSessionStore` · `RedisGuards` · `PasswordHasher` · `ClientRegistry` · `WriteOperationLogger` | 端口的实现；`ClientRegistry` 只被 api 层的过滤器直接使用，不是领域端口 |
| cli | 部署期命令 | `init-admin` · `add-account` · `generate-service-key`；复用用例，不是运维入口 |

**用例与端口**（端口定义在 domain，由 infrastructure 实现）：

| 端口 | 基础设施实现 | 使用它的用例 |
|---|---|---|
| `UserRepository` · `AccountRepository` · `CredentialRepository` | JDBC 仓储（PostgreSQL） | 登录（读账号、凭据、用户状态）· 用户列表 / 详情 / 新建 / 停用 / 启用 · 在线用户与用户会话（补全名称）· 改密 |
| `UserStatusLock` | JDBC 仓储（`pg_advisory_xact_lock`） | 停用、启用（串行化，保证「最后一个正常用户」） |
| `SessionStore` | `RedisSessionStore`（Lua） | 登录 · 校验 · 退出 · 停用后终止其全部会话 · 在线用户 · 用户会话 · 强制下线（同一脚本里原子留下短期墓碑 `uam:term:{sha}`，TTL `uam.session.terminated-notice-ttl` 默认 10 分钟；`verify` 找不到会话时据此回 `reason: terminated`） |
| `LoginThrottle` · `PasswordChangeThrottle` | `RedisGuards` | 登录 · 改密 |
| `IdempotencyStore` | `RedisGuards` | 新建用户 |
| `PasswordHasher` | Argon2id | 登录（校验）· 新建（哈希）· 改密 |
| `WriteOperationLog` | `WriteOperationLogger` | 新建 · 停用 · 启用 · 强制下线 · 改密 |
| `Clock` | 系统时钟 | 所有涉及时间的用例 |

#### L3 领域模型（UAM domain 层）

源文件：[domain-class.puml](./diagrams/domain-class.puml) · [domain-state-user.puml](./diagrams/domain-state-user.puml) · [domain-state-session.puml](./diagrams/domain-state-session.puml)

![L3 领域模型](./diagrams/domain-class.svg)

**统一语言**（与 spec 一致）：

| 术语 | 含义 |
|---|---|
| 用户（User） | 人的资料；有状态 `ACTIVE` / `DISABLED`；名字不要求唯一 |
| 账号（Account） | 登录主体，隶属于恰好一个用户；本期只有「账号名 / 密码」一种；账号名规范化后全局唯一，创建后不可改 |
| 凭据（Credential） | 密码账号的密码哈希（Argon2id）；明文永不落库、不进日志 |
| 会话（Session） | 账号一次成功登录的登录态；**与账号一对一**；通过账号隶属于唯一的用户 |
| 会话标识 / 会话引用 | 标识是机密（只在 cookie 里，Redis 只存哈希）；引用是非机密的 UUID，页面与接口只用它 |
| 操作人（OperatorContext） | 发起操作的运维人员：用户、账号、会话引用；请求级，由客户端传入，UAM 信任 |
| 客户端（Client） | 调用 UAM 的服务（**不是浏览器**），由服务密钥识别；有名称、至多两把密钥、权限；部署配置，不入库 |
| 在线用户 | 至少有一个未过期、未终止的会话的用户 |

**聚合**：

- **User 聚合（PostgreSQL）**：根是 `User`，内含 `Account`（0..*）与 `Credential`（每个密码账号一条）。一致性边界：新建用户时「用户 + 账号 + 凭据」在同一个事务里创建；停用 / 启用只改 `User`。
- **Session 聚合（Redis）**：根是 `Session`，只以 `accountId` / `userId` 引用 User 聚合，不嵌入它。用户与会话之间没有外键，一致性靠用例保证（停用后终止会话、登录后复查用户状态）。
- `OperatorContext` 与 `Client` 不是业务数据：`Client` 决定「谁能调」，`OperatorContext` 说明「代谁调」。

**不变量**（每条都要有测试，并发规则要有确定性的并发测试）：

| 不变量 | 由谁保证 | 对应 spec |
|---|---|---|
| 账号名规范化后全局唯一（含已停用用户的账号） | PostgreSQL 唯一索引 + `AccountNameRules` | FR-022 |
| 同一账号任一时刻至多一个有效会话，新登录顶掉旧会话 | `RedisSessionStore` 的 Lua 脚本（原子） | FR-004 |
| 账号能登录 = 账号 `ACTIVE` 且所属用户 `ACTIVE`；失败时统一提示、不区分原因 | `Account.canLogin` + 登录用例（先哈希比对再判状态） | FR-005 |
| 不能停用操作人自己所属的用户；不能停用最后一个正常用户（并发下也成立） | `SelfProtectionRule` + 咨询锁串行化 | FR-025 |
| 不能强制下线操作人自己正在使用的会话 | `SelfProtectionRule` | FR-030 |
| 停用用户后其全部会话立即结束，且不会留下已停用用户的有效会话（含并发登录） | 停用用例（提交后终止会话）+ 登录用例（写会话后复查状态） | FR-016、FR-024 |
| 新建用户：用户 + 账号 + 凭据同事务；同键重放返回原结果；并发抢同一账号名只有一个成功 | 创建用例 + `IdempotencyStore` + 唯一索引 | FR-021 ~ FR-023 |
| 改密只改指定账号，需要当前密码，当前密码连续错误按来源地址限制 | `Credential.change` + 改密用例 + `PasswordChangeThrottle` | FR-026、FR-027 |
| 机密会话标识只存哈希，对外只用会话引用；密码、哈希、服务密钥不进日志 | `RedisSessionStore` + 统一脱敏 | FR-031、FR-042 |
| 写操作必须带操作人，缺失即拒绝；每次写操作留一条日志 | `OperatorContextResolver` + `WriteOperationLog` | FR-008、FR-034 |

**状态**：

![User 状态](./diagrams/domain-state-user.svg)

![Session 生命周期](./diagrams/domain-state-session.svg)

**策略**（无状态的领域规则，参数可配置）：`AccountNameRules`（3–32 位字母数字 `_` `.` `-`，大小写不敏感）、`PasswordPolicy`（≥ 10 位且含字母与数字，改密须不同于当前）、`SessionPolicy`（空闲 30 分钟 / 绝对 12 小时）、`LoginThrottlePolicy`（账号 + 来源地址，5 次 / 15 分钟，不锁账号）、`SelfProtectionRule`。

**与存储的对应**：`User` → 表 `app_user`，`Account` → `account`，`Credential` → `credential`，`LoginName.normalized` → `account.login_name_norm`（唯一索引），`Session` → Redis 键 `uam:sess:*` / `uam:acct:*:sess` / `uam:sref:*` / `uam:online`（另有强制下线墓碑 `uam:term:*`）；`Client` 在配置里。字段、索引与 Redis 键见 [data-model.md](./data-model.md)。
## Project Structure

### Source Code

```text
├── uam/                                  # Spring Boot 4 · 用户 / 账号 / 会话的权威服务
│   ├── AGENTS.md  CLAUDE.md  pom.xml  .env.example
│   ├── src/main/java/com/s2u2m/uam/
│   │   ├── api/                          # REST 控制器、DTO、problem+json 映射、客户端认证过滤器（服务密钥，所有接口）、操作人请求头解析（资源接口）
│   │   ├── application/                  # 用例：Login、VerifySession、Logout、用户查询/新建/停用/启用、在线用户、会话终止、改密、AccountProvisioningService（创建用户 / 追加账号，被新建用例与 cli 共用）
│   │   ├── domain/                       # User、Account、Credential、Session、策略（密码、账号名、限流）；仓储与会话存储接口
│   │   ├── infrastructure/               # JDBC 仓储、Redis 会话存储与 Lua、Argon2id 编码器、写操作日志、脱敏
│   │   └── cli/                          # init-admin、add-account、generate-service-key：部署期一次性命令（不是运维入口）
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── db/migration/                 # Flyway：V1 表结构与 pg_trgm 索引 …
│   │   └── redis/                        # Lua 脚本：登录、终止、续期
│   └── src/test/java/com/s2u2m/uam/
│       ├── unit/  integration/  contract/  concurrency/  architecture/
```
