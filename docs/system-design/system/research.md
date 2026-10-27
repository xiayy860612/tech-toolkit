# Research（系统级 / 跨服务）: Admin Center 用户管理与会话管理

> **过程记录（系统级 / 跨服务）**：原文为 `specs/002-admin-center-uam/research.md`，归档时按 app 拆分：跨服务决策在 [system](./research.md)（R1–R6、R11、R18、R20、R23、风险）、UAM 在 [uam](../uam/research.md)（R7–R10、R15–R17、R22）、Admin Center 在 [admin-center](../admin-center/research.md)（R12–R14、R19、R21）；条目编号 R1…R23 全局不变。当前真实状态以各目录 `README.md` 与代码为准。

**Feature**: [spec.md](../../achieve-specs/admin-center/spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-23

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

- **Decision**: 单仓多应用：`apps/uam`（Spring Boot）与 `apps/admin-center`（Next.js）；共享契约先放本特性 `contracts/uam/openapi.yaml`，第一个实现任务把它迁到仓库根 `contracts/uam/openapi.yaml` 作为唯一权威；本地依赖与多实例编排放 `devops/local/`；压测脚本放 `devops/load/k6/`。各应用目录按根 `AGENTS.md` 带 `AGENTS.md` 与 `CLAUDE.md`。
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

## 4. 认证与安全（跨服务部分）

### R11 请求来源地址

- **Decision**: Admin Center 从入口反向代理设置的可信头中，按配置的「可信跳数」取客户端地址，放进登录与改密请求体的 `clientIp`；只有被授予断言权限的客户端（经服务密钥识别，R4）才可提交该字段，UAM 信任它。用户自带的 `X-Forwarded-For` 不被直接采信。
- **Rationale**: 失败限制的来源维度必须不可伪造。
## 6. 可观测性与扩展（跨服务部分）

### R18 可观测性与横向扩展

- **Decision**: 两个服务都无实例本地状态：UAM 的状态在 PostgreSQL / Redis，Admin Center 无存储；任一实例可处理任一请求，**负载均衡无需会话粘滞**。结构化 JSON 日志；Micrometer 指标；服务间传递 W3C `traceparent`；存活 / 就绪探针（UAM 就绪检查依赖 Redis 与 PostgreSQL）；优雅停机以满足 SC-012（滚动重启 0 次意外登出）。本地多实例编排：`devops/local/docker-compose.scale.yml` + nginx。
- **Rationale**: 直接对应 FR-037 ~ FR-040 与 SC-010 ~ SC-012。
## 7. 测试（跨服务）

### R20 测试与覆盖率（根 AGENTS.md「测试先行」与 ≥ 90% 门禁）

- **Decision**: 每个任务先写会失败的测试（red → green → refactor）。
  - UAM：JUnit 5 + AssertJ + Mockito 单元测试；Testcontainers（PostgreSQL、Redis）集成测试；基于 OpenAPI 的契约测试；ArchUnit 分层测试；**并发测试**（并发登录同账号、并发建同名用户、并发停用最后两个用户、停用与登录交错）；日志脱敏测试；客户端认证测试（缺失 / 错误 / 已撤销密钥 → 401，无断言权限 → 403，轮换期两把密钥同时有效，密钥不进日志）。JaCoCo 行覆盖率 ≥ 90%，排除路径仅限启动类、纯配置类、生成代码。
  - Admin Center：Jest + React Testing Library；msw 的 UAM 桩按 OpenAPI 校验请求与响应；Playwright E2E 与视口矩阵（320 / 375 / 768 / 1024 / 1440 / 2560）；`@axe-core/playwright`。覆盖率 ≥ 90%，排除 `src/components/ui/**`、生成代码、`*.d.ts`。
  - 扩展与弹性：k6（SC-009、SC-011）；滚动重启脚本（SC-012）。
- **Rationale**: 契约变更须先改契约与失败用例（根 AGENTS.md）；并发规则是本特性最容易出错的部分，必须有确定性的并发测试。
## 8. 部署与风险

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
