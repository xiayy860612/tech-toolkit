# Admin Center + UAM — 系统级架构（现状归档）

> **归档状态**：特性 `002-admin-center-uam` 已交付并归档。本文是**跨服务（系统级）架构与实现**的当前真实状态；需求规格见 [docs/achieve-specs/admin-center/spec.md](../../achieve-specs/admin-center/spec.md)；需求对照、验证记录与已知限制见 [traceability](../system/traceability.md)，UAM 领域与接口见 [domain-and-api](../uam/domain-and-api.md)。
> 设计按 app 拆分：[system](./README.md)（跨服务）· [uam](../uam/README.md) · [admin-center](../admin-center/README.md)；各目录的 `plan.md`、`research.md`、`data-model.md` 是过程记录，与本文不一致时**以本文和代码为准**。
> 图的 PlantUML 源文件与 SVG 在各目录的 `diagrams/` 下；改图后重新渲染：`java -jar plantuml.jar -tsvg -charset UTF-8 docs/system-design/*/diagrams/*.puml`（GitHub 不渲染 PlantUML，SVG 须与源文件一同提交）。

## 1. 总览

| 服务 | 路径 | 技术 | 状态 |
|---|---|---|---|
| UAM | `apps/uam` | Java 21、Spring Boot 4.0.x（Web MVC、Validation、JDBC、Data Redis / Lettuce、Actuator、Flyway 社区版）、`spring-security-crypto` + BouncyCastle（Argon2id）、Micrometer | 无实例本地状态；状态在 PostgreSQL 与 Redis |
| Admin Center | `apps/admin-center` | TypeScript 5、Node 22、Next.js 15（App Router、SSR、Server Actions）、React 19、Tailwind CSS 4、shadcn/ui（Radix）、zod | 无状态、无存储；`output: "standalone"` |
| PostgreSQL 16 | — | 用户、账号、凭据（强一致） | 单主 |
| Redis 7 | — | 会话、索引、限流计数、幂等键；AOF `everysec` | 单主，不使用 Cluster |
| 契约 | `contracts/uam/openapi.yaml` | OpenAPI 3.1，错误体 RFC 9457 `problem+json` | UAM 契约唯一权威 |

两个服务可各自独立增减实例；任一实例处理任一请求，**不需要会话粘滞**。
## 2. C4 视图

### 2.1 L1 系统上下文

![L1](./diagrams/c4-l1-context.svg)

运维人员经浏览器使用「用户与会话管理系统」（Admin Center + UAM）。将来的业务服务可作为 UAM 的客户端（本期没有）。服务密钥与客户端清单由密钥管理在部署时注入；日志、指标与告警由监控平台收集。源文件：[c4-l1-context.puml](./diagrams/c4-l1-context.puml)。

### 2.2 L2 容器

![L2](./diagrams/c4-l2-container.svg)

| 容器 | 职责 |
|---|---|
| 入口层（nginx / 负载均衡） | TLS 终止；无粘滞轮询；追加可信代理头 `X-Forwarded-For`（供 Admin Center 取终端来源地址）。本仓库只提供本地编排用的 nginx（§4），生产入口是部署配置。 |
| Admin Center | 登录页、User Manager、Session Manager；浏览器访问基于会话（cookie）；每个请求先向 UAM `verify`；不存密码、不连数据库。 |
| UAM | 认证、会话、用户与账号；每个接口先校验服务密钥；不使用任何用户凭据。 |
| PostgreSQL | 用户、账号、凭据。 |
| Redis | 会话、在线索引、登录与改密限流计数、幂等键。 |

约定：浏览器 → Admin Center 为 HTTPS，cookie 只含机密的会话标识；Admin Center → UAM 为 REST/JSON（`Authorization: Bearer <服务密钥>`，写操作与会话页另带 `X-Operator-*`）；**网络边界**：UAM 只对 Admin Center 实例开放，PostgreSQL 与 Redis 只对 UAM 开放（部署配置，应用不实现 IP 白名单；与服务密钥是两层保护）。源文件：[c4-l2-container.puml](./diagrams/c4-l2-container.puml)。
### 2.3 L3 组件

各服务内部组件见 [UAM §2.1](../uam/README.md) 与 [Admin Center §2.1](../admin-center/README.md)；领域模型见 [UAM §2.2](../uam/README.md)。

## 3. 运行时视图

### 3.1 登录

![登录](./diagrams/seq-login.svg)

要点：Admin Center 同源校验、必填校验（为空不调用 UAM）、取终端来源地址（取不到则拒绝登录）；UAM 先在 Redis 原子占用限流名额（`throttle-admit.lua`，超限 429）→ 读账号 / 用户 / 凭据 → **总是先哈希比对**（账号不存在比对伪哈希，使耗时无差别）→ 再判状态；`login.lua` 原子写新会话并顶掉旧会话；写入后**复查用户状态**（已停用则终止刚建的会话并拒绝，覆盖停用与登录并发的两种交错）；成功清零该来源的计数；哈希需要升级时用条件更新（密码已被改过则空操作）。cookie `__Host-asid`：`HttpOnly; Secure; SameSite=Strict; Path=/`，不设 `Domain`，`Max-Age` = UAM 返回的剩余绝对寿命。源文件：[seq-login.puml](./diagrams/seq-login.puml)。

### 3.2 每个请求：校验会话并调用 UAM

![每个请求](./diagrams/seq-verify-and-call.svg)

要点：`verify` 只读 Redis（会话 HASH 内已快照用户名、账号名、种类、到期；会话不存在时再读一次墓碑 `uam:term:{sha}` 以决定 401 是否带 `reason: terminated`），**不访问 PostgreSQL**；距上次写入 > 60 秒才 `touch.lua` 续期（避免每个页面请求一次写）。随后的读调用只带服务密钥，写调用另带 `X-Operator-*`。会话数据只存在于 Admin Center 服务端的单次请求内存：不写 cookie、不进浏览器、不进日志。`unverified`（超时 / 5xx / 连接失败 / UAM 拒绝了 Admin Center）保留 cookie，提示服务暂不可用。源文件：[seq-verify-and-call.puml](./diagrams/seq-verify-and-call.puml)。

### 3.3 终止会话（强制下线；停用 / 退出同理）

![强制下线](./diagrams/seq-terminate-session.svg)

强制下线：`SelfProtectionRule`（目标 == 操作人会话引用 → 409）→ `terminate.lua`（按 `uam:sref:{ref}`；`uam:acct:*:sess` 仅在仍指向该会话时才删；同一脚本原子写墓碑 `uam:term:{sha}`）→ `WAIT 1` → 写 `uam.admin.write`（被拒绝的也记录）。**停用用户**走另一条路径：事务内 `pg_advisory_xact_lock` → 自我保护 → 置 `DISABLED` → **提交后**对其全部账号 `terminate-all.lua` → `WAIT 1`。**退出**：由会话标识找到会话引用后 `terminate.lua`，幂等。被终止的会话下一次请求 `verify` → 401 `session_invalid` → `anonymous`；**只有管理员强制下线**留下 10 分钟墓碑，此时 401 另带 `reason: terminated`，登录页据此显示「你的会话已被管理员强制下线」，其余结束方式（退出、到期、被取代、停用）不带 `reason`，仍是通用的「会话已结束，请重新登录。」。源文件：[seq-terminate-session.puml](./diagrams/seq-terminate-session.puml)。

## 4. 部署视图

### 4.1 单实例 E2E 栈（`devops/local/docker-compose.stack.yml`，项目名 `uam-e2e`）

PostgreSQL、Redis、UAM、Admin Center 各一个；仅 Admin Center 向宿主机发布 `127.0.0.1:3000`，**UAM 不映射宿主机端口**。配置来自 `devops/local/e2e.env`（**仅限本地 E2E 的固定测试密钥**，禁止用于其他环境）；`devops/local/scripts/bootstrap.sh up|down` 构建并启动 / 关闭，并用 `init-admin` 建首个用户。栈前面没有反向代理，E2E 用例统一带 `X-Forwarded-For`。

### 4.2 多实例栈（`devops/local/docker-compose.scale.yml`，项目名 `uam-scale`）

3 个 Admin Center + 3 个 UAM + PostgreSQL + Redis + 2 个 nginx。三个网络：

| 网络 | 成员 | 是否发布端口 |
|---|---|---|
| `edge` | 入口 nginx、Admin Center 实例 | 入口 nginx 发布 `127.0.0.1:8088`（轮询入口）与 `8091 ~ 8093`（钉住第 1 ~ 3 个实例，**仅供验证脚本**） |
| `uam-net` | Admin Center 实例 ↔ `uam-lb`（内部 nginx）↔ UAM 实例 | 内部网络，不发布 |
| `data` | UAM 实例 ↔ PostgreSQL / Redis | 内部网络，不发布 |

- **入口 nginx**（`nginx/nginx.conf` + `proxy-params.conf`）：轮询、**无粘滞**（不使用 `ip_hash` / `hash` / cookie 映射）；上游 keepalive，`keepalive_time 5s` / `keepalive_timeout 4s`（周期性回收长连接，让停机中的实例能在宽限期内排空，避免被 SIGKILL）；`proxy_next_upstream error timeout http_502 http_503`，3 次 / 5 秒；**POST 不被重放**；`Host` 用 `$http_host` 并设 `X-Forwarded-Host`（非默认端口下 Server Action 的 Origin / Host 比较需要）。
- **`uam-lb`**（`nginx/uam-lb.conf`）：Admin Center 的 `UAM_BASE_URL=http://uam-lb:8080`，轮询到 3 个 UAM；**仅对 `POST /v1/auth/sessions/verify` 允许 `non_idempotent` 重试**（verify 只读、重放无副作用）；其余 POST 不重放；日志不记 `Authorization` 与请求体。
- 容器停机宽限：UAM `stop_grace_period 40s`（大于 `spring.lifecycle.timeout-per-shutdown-phase=30s`），Admin Center `20s`；UAM 就绪探针 `/actuator/health/readiness`（含 PostgreSQL 与 Redis），Admin Center 探活用 `GET /login`（匿名不调用 UAM，只反映本进程是否在服务）。

### 4.2.1 Kubernetes 部署（`devops/k8s/`）

清单要求**通用**：同一份 `base/` 既能部署到本地 kind，也能直接用在云上的 k8s；环境差异只放在 `overlays/` 与 `components/`（kustomize）。

| 位置 | 内容 |
|---|---|
| `base/` | UAM ×2、Admin Center ×2（Deployment + ClusterIP Service + PodDisruptionBudget）、入站默认拒绝与「Admin Center → UAM」放行的 NetworkPolicy。**不含** Namespace、数据库、入口、Secret、环境专属镜像名 |
| `components/ingress` | 标准 `Ingress`（`ingressClassName` / host / TLS 由 overlay 补丁）+ 放行 ingress controller → Admin Center 的 NetworkPolicy；云上用（Ingress 路径，kind 不用） |
| `components/gateway` | Gateway API：`Gateway`（监听）+ `HTTPRoute`（路由到 Admin Center）+ 放行数据面代理 → Admin Center 的 NetworkPolicy；`gatewayClassName`、监听 / TLS 由 overlay 补丁。目前只有 kind 用 |
| `components/in-cluster-data` | PostgreSQL 16 / Redis 7 的 StatefulSet + PVC + 「UAM → 数据层」放行策略；**仅用于 kind / 开发**，云上用托管服务 |
| `overlays/kind` | base + gateway + in-cluster-data；入口为 Gateway API（Envoy Gateway），经 port-forward 用 IP 访问；镜像 `tech-toolkit/*:local`；`SESSION_COOKIE_NAME=asid`（本地 http） |
| `cluster/envoy-gateway-class.yaml` | Envoy Gateway 的 `GatewayClass` + `EnvoyProxy`（数据面 Service 用 ClusterIP：kind 没有 LoadBalancer，否则 Gateway 停在 `Programmed=False`）。Envoy Gateway v1.9.3（含 Gateway API v1.6.1 的 CRD，约 4 MB）由 `deploy.sh up` 按固定版本从官方 release 安装，**只给 kind 装** |
| `overlays/cloud` | base + ingress；镜像取 CI 推送的 ACR 仓库；cookie 用默认 `__Host-asid`（要求 HTTPS）；**示例**，host / 镜像标签 / ingress 类按实际环境改 |

**Secret 契约**（清单只按固定名字引用，不含任何值；kind 由 `scripts/deploy.sh` 生成，云上由云密钥服务 / External Secrets / CI 创建）：

| Secret | 键 | 说明 |
|---|---|---|
| `uam-runtime`（`envFrom`） | `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`、`SPRING_DATA_REDIS_HOST` / `_PORT`、`UAM_CLIENTS` | 托管 Redis 需要密码 / TLS 时，直接在同一个 Secret 里加 `SPRING_DATA_REDIS_PASSWORD`、`SPRING_DATA_REDIS_SSL_ENABLED` |
| `admin-center-runtime`（`envFrom`） | `UAM_SERVICE_KEY` | 与 `UAM_CLIENTS` 里的哈希成对（轮换步骤见 UAM 文档） |
| `postgres-credentials` | `POSTGRES_DB` / `_USER` / `_PASSWORD` | 只有 `in-cluster-data` 用 |

要点：UAM 只有 ClusterIP，NetworkPolicy 只放行 Admin Center（策略要求 CNI 支持，不支持时策略不生效但也无害）；`TRUSTED_PROXY_HOPS` 必须等于 Admin Center 前面的可信代理层数（ingress controller 直接面对 L4 负载均衡为 `1`；前面再有 L7 负载均衡 / CDN 则为 `2`，且该层必须追加 `X-Forwarded-For`）；镜像不写死标签，上线用 `kustomize edit set image` 钉到构建号或 sha；探针与停机宽限与 §4.2 一致（UAM `terminationGracePeriodSeconds` 40s + `preStop` 5s）。

### 4.3 来源地址的信任模型（`X-Forwarded-For`）

登录与改密失败限制的**来源维度必须不可伪造**。Admin Center 只采信**右数第 `TRUSTED_PROXY_HOPS` 项**（默认 1）：每个可信代理把它看到的对端地址追加在 `X-Forwarded-For` 末尾，所以该项由可信代理写入；其左侧的内容可能是用户自带的，一律不采信。项数不足、`hops` 为 0 或取到的不是合法 IP → 取不到来源地址，登录 / 改密被拒绝（不把任意字符串交给 UAM）。只有被授予断言权限的客户端才能在请求体里提交 `clientIp`，UAM 信任它。本地多实例栈上 `scale-checks.sh` 用「每次带不同的伪造最左项连续错误登录，第 6 次仍被限流」验证了这一点。**`TRUSTED_PROXY_HOPS` 必须等于 Admin Center 前面可信代理的实际跳数**（部署配置）。
### 4.4 容器镜像

各应用的镜像见 [UAM §8](../uam/README.md) 与 [Admin Center §6](../admin-center/README.md)。

## 5. 跨服务安全

各服务自己的安全设计见 [UAM §4](../uam/README.md) 与 [Admin Center §3](../admin-center/README.md)。跨服务的部分：

| 主题 | 设计与实现 |
|---|---|
| **两层保护** | 服务密钥（UAM 校验，见 UAM §4）+ 网络边界（UAM 只对 Admin Center 实例开放，PostgreSQL 与 Redis 只对 UAM 开放，见 §2.2）。 |
| **来源地址** | 见 §4.3；Admin Center 取地址（`ClientIp`），UAM 信任被授予断言权限的客户端提交的 `clientIp`。 |
| **安全检查脚本** | `devops/local/scripts/security-check.sh [static\|runtime\|all]`：static——无被跟踪的 `.env`（只允许 `*.example` 与 `devops/local/e2e.env`）、无数据库驱动 / ORM、工作区与历史里无完整长度 `uamk_` 密钥或私钥特征（允许名单与理由在脚本里）、`src/lib` 的 `server-only`、构建后 `.next/static` 无服务密钥、每个写入口先做同源校验；runtime（需运行中的 E2E 栈）——对 login、createUser、terminateSession、disableUser、enableUser、changePassword、`POST /logout` 各用外站 `Origin` / `Sec-Fetch-Site: cross-site` / 无来源头伪造一次，必须被拒且状态不变，最后扫描响应、两个服务的日志与浏览器脚本里没有密码、`$argon2`、会话标识、服务密钥。 |

## 6. 扩展性与一致性

### 6.1 无粘滞与跨实例一致

两个服务都无实例本地状态：UAM 的状态只在 PostgreSQL / Redis，Admin Center 无存储。任一实例签发的会话任一实例立即可 `verify`；会话终止后任一实例的下一次 `verify` 即判无效；经任一实例创建的用户立即可见（`TwoInstanceConsistencyIT`、`scale-checks.sh`）。

### 6.2 优雅停机与滚动重启

UAM：`server.shutdown=graceful`，`spring.lifecycle.timeout-per-shutdown-phase=30s`，就绪探针先变未就绪、在途请求完成后再退出（`GracefulShutdownIT`）。入口与 `uam-lb` 的重试 / 长连接回收配置见 §4.2；`rolling-restart.sh` 对 ≥ 15 秒的重启给出疑似 SIGKILL 的警告。已记录的结果（本地 3+3 编排，非独占机器）见 [devops/load/RESULTS.md](../../../devops/load/RESULTS.md) §3；写操作在重启期间的行为**未验证**（POST 不被重放）。
## 7. 可观测性（跨服务）

各服务的日志、事件与指标见 [UAM §6](../uam/README.md) 与 [Admin Center §4](../admin-center/README.md)。

| 项 | 实现 |
|---|---|
| trace | UAM 的 `TraceContextFilter` 接受合法 `traceparent`（否则新建）并在响应回显；Admin Center 把**请求自带**的 `traceparent` 透传给 UAM（入口 nginx 本地编排不生成 trace） |
| 排障入口 | 页面「服务暂不可用」：先查 UAM 的 `uam.client.rejected`（`reason`），再查 Admin Center 的 `admin-center.uam.misconfigured`；两者都没有则是 UAM 不可达或超过 `UAM_TIMEOUT_MS`。详见两个应用的 `AGENTS.md` |

## 8. 本地专项脚本与 CI

本地专项脚本（不在 CI 里）：`devops/local/scripts/{bootstrap,scale-checks,rolling-restart,rotate-key,security-check}.sh`、`devops/load/k6/*`。各服务的 CI 与覆盖率门禁见 [UAM §7](../uam/README.md)、[Admin Center §5](../admin-center/README.md)；命令与环境变量的权威说明在 [`apps/uam/AGENTS.md`](../../../apps/uam/AGENTS.md) 与 [`apps/admin-center/AGENTS.md`](../../../apps/admin-center/AGENTS.md)（根 `AGENTS.md` 不写命令）。

## 9. 与过程文档的关系

- 过程记录：[plan.md](./plan.md)、[research.md](./research.md)（跨服务）；[UAM](../uam/)、[Admin Center](../admin-center/) 各有自己的 `plan.md` / `research.md`；[数据模型](../uam/data-model.md)；[界面原型](../admin-center/prototype/README.md)。
- 需求规格：[spec.md](../../achieve-specs/admin-center/spec.md)（仅用户需求描述）；技术性需求条款（服务密钥、会话标识等，FR-008、FR-013 ~ FR-015、FR-042 ~ FR-044、US12、SC-016）：[technical-requirements.md](./technical-requirements.md)；验证与任务：[quickstart.md](./quickstart.md)、[tasks.md](./tasks.md)、[检查清单](./checklists/requirements.md)；Web 路由契约：[web-routes.md](../admin-center/web-routes.md)。
- 需求对照、验证记录、已知限制：[traceability.md](./traceability.md)；UAM 领域模型、接口与流程：[domain-and-api.md](../uam/domain-and-api.md)；Admin Center 会话三态：[session-states.md](../admin-center/session-states.md)。
