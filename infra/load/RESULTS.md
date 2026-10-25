# US10 验证结果（quickstart S13 ~ S15）

**日期**：2026-10-24。**本文只记录实际测得的数字**；没有测的项目在 §6 明确列出，不下结论。
被测对象：`infra/local/docker-compose.scale.yml`（3 个 Admin Center + 3 个 UAM + PostgreSQL + Redis + nginx）。

## 1. 环境

| 项 | 值 |
|---|---|
| 机器 | Apple M4 Pro，12 核，24 GiB 内存，macOS 26.4.1；开发笔记本，**非独占机器**（同时运行 IDE、浏览器与 Claude Code 会话） |
| 容器运行时 | Docker Desktop，Engine 29.1.2，Compose v2.40.3；Docker 虚拟机 12 CPU / 7.65 GiB（所有容器与 k6 共用） |
| UAM | Spring Boot 4.0.9（Tomcat 11.0.27），Temurin JRE 21，每实例 `-XX:MaxRAMPercentage=12`（约 0.9 GiB 上限） |
| Admin Center | Next.js 15.5.28，Node v22.23.3（alpine） |
| 存储 | PostgreSQL 16.15（`shared_buffers=256MB`），Redis 7.4.11（AOF everysec） |
| 入口 / 内部负载均衡 | nginx 1.27.5，各 1 个 worker，轮询、无粘滞、上游 keepalive |
| 压测 | k6 v2.3.0（`grafana/k6`，作为 compose 的 `k6` 服务运行，与入口和 uam-lb 同网） |
| 配置 | `infra/local/e2e.env` 的本地测试值；UAM 与 Admin Center 的 `UAM_TIMEOUT_MS` 为默认 1500 |

资源快照（10 万用户 / 1 万会话、压测刚结束，`docker stats`）：每个 UAM 约 515 ~ 593 MiB，每个 Admin Center 约 146 ~ 152 MiB，PostgreSQL 约 1018 MiB，Redis 约 35 MiB（`used_memory` 15.7 MiB）。

## 2. S13 无会话粘滞与多实例一致（SC-010、FR-037 ~ FR-040）

命令：`infra/local/scripts/scale-checks.sh check`（31 项断言）。运行 3 次，**每次 31 通过 / 0 失败**：新建的栈上 1 次；已灌入 10 万用户 / 1 万会话的栈上 1 次；`down` 后从零 `up` 的栈上 1 次。

断言内容（均实际执行）：

- 登录一次后，经入口轮询连续 30 次访问 `/users`，30 / 30 已登录；钉在 Admin Center 实例 1 / 2 / 3 各访问 `/users`、`/sessions` 各 5 次，各 10 / 10 已登录。
- 入口的请求落点（nginx 日志的 `$upstream_addr`）：脚本现在只统计 30 次轮询扫描本身（日志标记紧贴扫描之前、在钉住的请求发出之前读取），断言 3 个实例各自至少收到 8 次（理论值各 10 次，余量留给入口 nginx 自己的健康检查）、上游地址种类 3 个。此前记录的「20 / 20 / 21」包含了钉住的请求（每个实例各 10 次），不能证明无粘滞，该数字作废；修正后的断言已重新实测（2026-10-24，全新多实例栈）：30 次轮询扫描在 admin-center-1 / 2 / 3 上各收到 **10 / 10 / 10** 次，上游地址种类 3 个，S13 共 31 项全部通过。第一次运行时 uam-lb 的 verify 分布为 40 / 40 / 40，3 个 UAM 各自的 `http_server_requests` verify 计数都增加。
- 经 Admin Center 实例 1 新建用户 → 实例 2、3 的用户列表与详情立即可见 → 在实例 3 登录成功 → 该会话在实例 1、2、3 都已登录。
- 经实例 2 退出 → 实例 1、2、3 的下一次请求都视为未登录。
- 用 UAM `DELETE /v1/sessions/{ref}` 强制下线（经 uam-lb，操作人为管理员会话）→ 返回 204 → 实例 1、2、3 的下一次请求都视为未登录；管理员自己的会话不受影响。
- 终端来源地址不可伪造：连续 6 次错误登录，每次带不同的伪造 `X-Forwarded-For` 最左项，第 6 次被限流（说明 Admin Center 采信的是 nginx 追加的最右项，`TRUSTED_PROXY_HOPS=1`）。
- 另有 UAM 层的两实例测试：`TwoInstanceConsistencyIT`（7 项）与 `GracefulShutdownIT`（1 项），见 §7。

偏差：强制下线用的是 UAM 接口，不是 Admin Center 页面的确认框。该确认框的表单在服务端渲染的 HTML 里不存在（对话框打开后才由客户端渲染），无法用 curl 提交；Admin Center 页面里强制下线的 E2E 由 `us6-terminate.spec.ts` 覆盖，不在本次多实例运行里。本次验证的是「终止发生后任一 Admin Center 实例的下一次请求都判未登录」。

## 3. S14 滚动重启（SC-012、FR-039）

命令：`infra/local/scripts/rolling-restart.sh`。流程：5 个同时有效的会话（管理员 + 4 个额外用户），4 个 worker 经入口持续访问 `/users`、`/sessions`、`/users?q=roll`；依次 `docker compose restart` uam-1 → uam-2 → uam-3 → admin-center-1 → admin-center-2 → admin-center-3，每个就绪后等 5 秒；结束后每个会话在 3 个 Admin Center 实例上都再访问一次。

**所有运行里意外登出始终为 0，重启后会话始终全部有效（15 / 15）。** 失败请求数随配置调整而变化，逐次如实列出（"失败" = 非 200、连接失败或页面提示「服务暂不可用」）：

| # | 配置 | 请求总数 | 意外登出 | 失败请求 | 备注 |
|---|---|---|---|---|---|
| 1 | 初版（轮询 + 重试） | 8031 | 0 | 0 | 每个实例重启 3 ~ 4 s |
| 2 | **对照：关闭 `proxy_next_upstream`** | 8132 | 0 | 6（3 页提示服务暂不可用，3 个 HTTP 502） | 每次重启各 1 个失败请求，说明脚本能测出失败，也说明重试确实在起作用；即使失败，用户也没有被登出 |
| 3 | 初版，栈上已有 1 万会话 | 7157 | 0 | 0 | **3 个 Admin Center 的重启各耗时 23 ~ 24 s**（≈ `stop_grace_period` 20 s + 启动），即优雅停机没有自行完成、被 SIGKILL（缺陷 A，见 §5） |
| 4 | 加 `keepalive_time 5s` | 3939 | 0 | 1（「服务暂不可用」`/sessions`） | Admin Center 重启降到 4 ~ 7 s；残留的 1 个失败来自缺陷 B |
| 5 | + `proxy_connect_timeout 250ms` | 4297 | 0 | 0 | |
| 6 | 同上 | 4237 | 0 | 1（`/sessions`） | |
| 7 | 同上 | 4168 | 0 | 0 | |
| 8 | 同上（加了时刻记录） | 4182 | 0 | 1（`/users`，时刻恰在 uam-1 重启开始） | 对照 uam-lb 日志：`POST /v1/auth/sessions/verify` 因「upstream prematurely closed connection」返回 502（缺陷 B） |
| 9 ~ 14 | + verify 允许重试（最终配置） | 4414 / 4050 / 4630 / 6577 / 7238 / 7774 | 0 | **0 / 0 / 0 / 0 / 0 / 0** | |
| 15 | 最终配置，`down` 后从零 `up` 的栈 | 9623 | 0 | 0 | |

最终配置下共 7 次运行（#9 ~ #15），合计 44,306 个请求（4414 + 4050 + 4630 + 6577 + 7238 + 7774 + 9623），**0 次意外登出，0 个失败请求**。请求总数的差异只因每次运行时长不同（取决于重启耗时与机器当时的负载），不是配置差异。最终配置下重启耗时：UAM 3 ~ 4 s，Admin Center 4 ~ 11 s，均未触及 SIGKILL 警告线（15 s）。

范围说明：持续访问用的是 GET 页面请求。Admin Center 的 POST（登录、Server Action、退出）在入口不会被重放；重启期间没有对写操作做并发验证。

## 4. S15 规模：SC-009 列表与详情（10 万用户、1 万有效会话）

**数据**：`infra/load/k6/seed-data.sh`。用户 / 账号 / 凭据用 SQL（`generate_series`）直接生成，全部复用管理员凭据的同一个 Argon2id 哈希（10 万行的「用户已就绪」步骤耗时 1 ~ 2 秒）；会话由 k6 经 uam-lb 调用 UAM 真实的登录接口生成（并发 48，10,000 次登录 36 ~ 37 秒，约 277 次 / 秒，全部 200）。实测库内：100,007 用户 / 100,007 账号 / 100,007 凭据，UAM 在线用户总数 10,005（含管理员与前面步骤留下的几个会话）。

**方法**：`infra/load/k6/list-and-detail.js`，经 Admin Center 入口访问真实页面（带会话 cookie，不跟随重定向，成功 = 200 且页面不是「服务暂不可用」）。`constant-arrival-rate`，先预热 15 s（不计入），再测 60 s。每次迭代轮流访问 5 类页面各 1/5：用户列表（随机页，共 5001 页）、用户搜索（随机账号名）、用户详情、在线用户列表（随机页，共 501 页）、某在线用户的会话页。响应时间 = k6 `http_req_duration`（收到完整响应）。

最终配置下的两次运行（阈值全部满足：每类页面与总体 p95 < 2000 ms，检查通过率 100%，失败率 0%）：

| 到达率 | 测量期请求数 | 总体 p95 | 用户列表 p95 | 用户搜索 p95 | 用户详情 p95 | 在线用户列表 p95 | 会话页 p95 | 失败 / 降级页 |
|---|---|---|---|---|---|---|---|---|
| 20 次 / 秒（总体平均 26.3 ms） | 1201 | 93.1 ms | 114.4 ms | 8.8 ms | 9.1 ms | 43.2 ms | 9.0 ms | 0 / 0 |
| 100 次 / 秒（总体平均 29.6 ms） | 6001 | 92.3 ms | 113.5 ms | 15.4 ms | 7.9 ms | 45.3 ms | 26.0 ms | 0 / 0 |

所有类别的 p99 ≤ 136 ms，最大值 ≤ 174 ms。结论：在上述数据量与负载下，SC-009 的 p95 ≤ 2 秒**满足，且余量很大**。注意口径限制：k6 度量的是服务端渲染的响应时间，不含浏览器渲染；SC-009 没有规定并发，本次最高 100 次 / 秒（其中在线用户列表 20 次 / 秒）。

### 4.1 在线用户列表的扩展性发现（`/v1/online-users`，`online.lua`）

SC-009 在上面的负载下满足，但在线用户列表有一个**与实例数无关的吞吐上限**：

- `online.lua` 在一次 Redis Lua 调用里扫描整个 `uam:online`（1 万成员，每个成员做 1 次 `GET` + 1 次 `HMGET`），UAM 再在内存里按用户聚合。实测 Redis 每次调用 **22.2 ~ 22.9 ms**（`INFO commandstats` 的 `evalsha usec_per_call`，各次运行：22.0、22.9、22.2、22.3、22.4 ms；该数字含运行期间少量其他脚本调用，量级以 online.lua 为主）。
- Redis 执行命令是单线程的，所以这 22 ms 内 Redis **不处理任何其他请求**（包括所有实例的会话校验）。理论上限 ≈ 1000 / 22.3 ≈ 45 次 / 秒的在线用户列表请求。

只压在线用户列表页（`KINDS=sessions_list`，测 30 s，预热 10 s），最终配置：

| 在线用户列表到达率 | p95 | 页面降级（提示服务暂不可用，即 UAM 调用超过 1.5 s） |
|---|---|---|
| 20 次 / 秒 | 38.2 ms | 0 |
| 35 次 / 秒 | 39.4 ms | 0 |
| 45 次 / 秒 | 1.5 s（即 UAM 超时） | 252 / 1759（14.3%） |

再加上其他页面（45 次 / 秒的在线用户列表 + 15 次 / 秒的用户详情混合）：**用户详情也受拖累，p95 1.5 s，499 / 2329 个页面降级（21.4%）**；也就是说，在线用户列表一旦把 Redis 占满，所有依赖 Redis 的请求（每个页面的会话校验）都会变慢。早先在调整 nginx 前的同类探测得到相同的形状（50 次 / 秒与 100 次 / 秒的在线用户列表几乎全部降级）。

**判断**：1 万会话规模下，SC-009 的单请求延迟没有风险；但这个页面的并发能力只有约 40 次 / 秒，超过后不是线性变慢，而是 Redis 被占满、拖累全部实例。1 万会话只有约 40 个同时点击「会话管理」的管理员的量级，现实风险低，但它是一个横向扩展的硬上限（加 UAM 实例无效），且会话数增长时线性变差（O(N)）。

**建议的最小修复（未实施，需要你确认）**：让 `online.lua` 不再对每个成员做 `GET` + `HMGET`。`uam:online` 的分数就是该会话的有效到期时间，登录、续期、终止、被取代都会在同一个 Lua 脚本里同步维护成员，research R9 本来设计的就是「最近活动 = 最大分数 − 空闲时长」。因此可以只做一次 `ZRANGEBYSCORE uam:online (now +inf WITHSCORES`，成员存在性由 ZSET 本身保证（过期的分数 ≤ now 已被排除），由 UAM 用分数推出 `lastActiveAt`。这样每次调用降为约一次 O(N) 的 ZSET 读取（量级为毫秒），不需要改数据结构或语义；需先补一个针对「ZSET 与会话键不一致时的行为」的测试，并确认 `lastActiveAt` 由分数推导与现有契约一致。备选：在 UAM 实例内做 1 ~ 2 秒的合并 / 缓存（会让列表有最多 1 ~ 2 秒的陈旧，与 SC-003 的「总与会话存在性一致」冲突，不推荐）；超过 10 倍规模时按 research R9 的扩展方案做按用户分片。

## 5. 联调中发现并修复的缺陷（均为 `infra/local` 配置层，应用代码无改动）

- **缺陷 A：Admin Center 在持续流量下不能优雅退出，被 SIGKILL。** Next.js 在 SIGTERM 时调用 `server.close()`，等所有连接结束；nginx 的上游长连接是最近使用优先，持续流量下会一直复用同一条「热」连接，它永远不空闲，实例要等到 `stop_grace_period` 才被杀（实测重启 20 s 以上，在途请求被截断；GET 靠 nginx 重试掩盖，POST 不会被重放）。修复：上游加 `keepalive_time 5s` 与 `keepalive_timeout 4s`（小于 Node 的 5 s `keepAliveTimeout`），让 nginx 周期性回收长连接；停机中的实例此后至多约 5 s 排空。验证：Admin Center 重启从 23 ~ 24 s 降到 4 ~ 11 s（§3 #3 → #4 之后）。`rolling-restart.sh` 对 ≥ 15 s 的重启给出疑似 SIGKILL 的警告。
- **缺陷 B：UAM 停机时空闲长连接被关，`verify` 的 POST 返回 502。** nginx 复用了 Tomcat 刚关闭的长连接，报「upstream prematurely closed connection」，默认不重放 POST，于是那个页面请求提示「服务暂不可用」（用户没有被登出，但这次请求失败；§3 #4、#6、#8 各 1 个）。修复：uam-lb 只对 `POST /v1/auth/sessions/verify` 开启 `non_idempotent` 重试（verify 只读，重放无副作用）；其余 POST 不重放（例如改密重放会得到「当前密码不正确」）。验证：§3 #9 ~ #15 共 7 次运行 0 个失败请求。
- **缺陷 C：多实例入口会让所有 Server Action 登录 / 表单提交失败。** `proxy_set_header Host $host` 会去掉端口，Next.js 的 Server Action 比较 `Origin`（带端口）与 `Host`，在非 80 / 443 端口下报「Invalid Server Actions request」。修复：用 `$http_host` 并设置 `X-Forwarded-Host`（`infra/local/nginx/proxy-params.conf`）。生产在 443 上不受影响，但任何非默认端口的入口都需要。

另外观察到、不是缺陷：3 个 UAM 在空库上**同时启动**时，Flyway 迁移由一个实例执行、其余等待，3 个实例都正常就绪（日志可见 uam-3 迁移、uam-2 / uam-1 随后确认 up to date）。

## 6. 没有测、不能下结论的项目

- **SC-011（1 → 4 实例吞吐 ≥ 3.2 倍）：没有测，也没有判定。** 需要独占机器、分机压测、实例是瓶颈（见 [SC-011.md](./SC-011.md)）。本机编排固定为 3 + 3 个实例，且所有组件与 k6 共用一个 12 核的 Docker 虚拟机，4 实例拓扑没有搭，1 实例拓扑也没有量。
  - 唯一跑过的是脚本冒烟：`validate-throughput.js`，当前 3 + 3 拓扑、已灌入 10 万用户 / 1 万会话、`VUS=32`、预热 10 s + 测量 30 s、`INSTANCES=3`：**106.8 次 / 秒，p95 420.5 ms，错误率 0**。这个数字**只说明脚本能跑、能产出结果行**，不是 SC-011 的任何一项判定（没有单实例基线、没有多轮中位数、机器不独占）。该负载里有 1/3 是在线用户列表，按 §4.1 的上限推算 Redis 会先于实例饱和（这是推论，没有单独验证），所以这个数字也不代表实例的扩容能力。
- 未验证：写操作在滚动重启期间的行为（POST 不重放，未并发压测）；Admin Center 页面里强制下线 / 停用的多实例 E2E（用 UAM 接口代替，见 §2）；浏览器端渲染时间；超过 1 万会话或 10 万用户的规模；跨机网络；Redis / PostgreSQL 自身的故障切换（本编排是单实例）。
- 单次运行：SC-009 的 §4 两次运行与在线用户列表的探测各只跑了 1 次（未做多轮取中位数）；滚动重启的最终配置有 7 次运行。
- 没有对 UAM 或 Admin Center 的应用代码做任何改动；覆盖率门禁未变。

## 7. UAM 集成测试（T158、T159）

- `TwoInstanceConsistencyIT`：7 项通过。第二个实例是同一 JVM 里独立的 Spring 上下文与随机端口，共享同一对 PostgreSQL / Redis。**非空洞验证**：把第二个实例的 Redis 改成另一个库（`--spring.data.redis.database=1`）后，7 项中 6 项失败（改回后全部通过）。
- `GracefulShutdownIT`：1 项通过。在一个真实启动的实例上，用一个阻塞到测试放行的探测路由造出在途请求；后台关闭实例后，就绪状态变为 `REFUSING_TRAFFIC` 时请求仍在处理、停机未结束，放行后请求以 200 完成，随后停机结束。**非空洞验证**：把该实例改为 `server.shutdown=immediate` 后，断言「就绪变为未就绪时在途请求仍在处理」失败（改回后通过）。
- 两个测试都没有发现 UAM 的配置或代码缺口（`server.shutdown=graceful` 与就绪探针已满足）。
