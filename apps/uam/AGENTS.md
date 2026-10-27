# AGENTS — apps/uam（UAM：用户、账号、会话的唯一权威）

遵循仓库根 [`AGENTS.md`](../../AGENTS.md)；本文件补充 UAM 的技术栈、命令与门禁。需求见 [`docs/achieve-specs/admin-center/spec.md`](../../docs/achieve-specs/admin-center/spec.md)，设计见 [`docs/system-design/uam/`](../../docs/system-design/uam/README.md)，权威契约见 [`contracts/uam/openapi.yaml`](../../contracts/uam/openapi.yaml)。

## 技术栈与目录

- Java 21、Spring Boot 4.0.x（Web MVC、Validation、JDBC、Data Redis（Lettuce）、Actuator、Flyway 社区版）、`spring-security-crypto` + BouncyCastle（Argon2id）、PostgreSQL 16、Redis 7。
- 根包 `com.s2u2m.uam`，分层 `api → application → domain ← infrastructure`（`domain` 不依赖任何框架，由 ArchUnit 守住）：
  - `api/`：REST 控制器、DTO、`problem+json` 错误映射、操作人上下文解析
  - `application/`：用例（登录、校验会话、退出、用户查询 / 新建 / 停用 / 启用、在线用户、会话终止、改密）与 `AccountProvisioningService`
  - `domain/`：`model`、`policy`、`port`
  - `infrastructure/`：JDBC 仓储、Redis 会话存储与 Lua 脚本、Argon2id、日志（`logging/`）、装配（`config/`）
  - `cli/`：部署期一次性命令 `init-admin`、`add-account`（不是运维入口）
- 资源：`src/main/resources/{application.yml,logback-spring.xml,db/migration/,redis/}`。
- UAM **不使用任何用户凭据**，**目前也不识别调用方，只靠网络隔离（k8s NetworkPolicy / 内网）；服务身份后续实现**。操作人由调用方以 `X-Operator-*` 请求头传入并被信任（见 research R5）。

## 命令（在仓库根目录执行）

```bash
# lint：格式检查 / 自动格式化（Spotless + google-java-format）
mvn -f apps/uam/pom.xml spotless:check
mvn -f apps/uam/pom.xml spotless:apply

# typecheck：编译
mvn -f apps/uam/pom.xml -DskipTests compile

# 单元测试（*Test）
mvn -f apps/uam/pom.xml test

# 完整门禁：单元 + 集成 / 契约 / 并发测试（*IT，需要本机 Docker）+ JaCoCo 行覆盖率检查 + 打包（target/uam.jar）
mvn -f apps/uam/pom.xml verify

# 本地运行：先启动 PostgreSQL 与 Redis，再注入连接（占位值换成自己的；真实值禁止入库）
cp devops/local/.env.example devops/local/.env   # 然后改 POSTGRES_PASSWORD
docker compose -f devops/local/docker-compose.yml --env-file devops/local/.env up -d
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/uam SPRING_DATASOURCE_USERNAME=uam SPRING_DATASOURCE_PASSWORD='<与 devops/local/.env 一致>'
export SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=6379
mvn -f apps/uam/pom.xml spring-boot:run
```

## 测试与覆盖率

- 测试先行（red → green → refactor）：新行为或缺陷修复先写会失败的测试。
- 单元测试 JUnit 5 + AssertJ + Mockito；集成 / 并发测试（`*IT`）用 Testcontainers 的真实 PostgreSQL 16 与 Redis 7（所以 `verify` 需要 Docker）；契约测试把响应按 `contracts/uam/openapi.yaml` 校验；ArchUnit 守分层。
- **覆盖率口径：行覆盖率（LINE）≥ 90%**（`pom.xml` 的 `jacoco.line.minimum`，规则作用于整个 BUNDLE），工具 JaCoCo，单元与集成测试合并统计（`target/jacoco-merged.exec`，报告在 `target/site/jacoco/`）；`mvn verify` 在低于阈值时失败，CI（`.github/workflows/uam-ci.yml`）同样执行。
- **排除路径**（`pom.xml` 里 JaCoCo 的 `excludes`，只允许放不含业务逻辑的代码，终审在 PR 评审）：
  - `**/UamApplication.class`：启动类，只委托给 Spring
  - `**/config/*Config.class`：纯装配类，目前是 `PolicyConfig`、`RedisConfig`
  - `**/generated/**`：生成代码（预留；目前没有匹配的类）
  - 注意：新增的 `infrastructure/config` 类若以 `Config` 结尾会被自动排除，因此那里不要放逻辑。

## CI 与镜像发布

`.github/workflows/uam-ci.yml`：`verify`（lint / 编译 / 测试 + 覆盖率）通过后，`image` 任务调用可复用工作流 `.github/workflows/docker-publish.yml`，用 `apps/uam/Dockerfile`（上下文为仓库根）构建镜像。

- **触发**：只有 `main` 的 push 才构建并推送到 `registry.cn-shenzhen.aliyuncs.com/s2u2m/uam`（阿里云 ACR，仓库需先在 ACR 建好）；PR 与其他分支只跑 `verify`，不构建镜像。
- **tag**：`<版本号>`（取自 `pom.xml` 的 `<version>`，如 `0.1.0-SNAPSHOT`）、`<构建号>`（工作流 `run_number`）、`<短 sha>`、`latest`。版本号不变时 `<版本号>` 会被覆盖，要定位确切产物用构建号或 sha。
- **所需 secrets**（仓库 Settings → Secrets and variables → Actions）：`IMG_REPO_USERNAME`、`IMG_REPO_PASSWORD`（阿里云 ACR 的登录用户名与固定密码）。

## 环境变量与示例配置

真实值只通过部署环境注入，禁止入库；示例见 `devops/local/.env.example`（本地依赖）与 `devops/local/e2e.env`（本地 E2E 栈，**仅限测试**的固定值）。

| 变量 | 说明 | 默认 |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL 连接 | 必填 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | Redis 连接 | 必填 |
| `SPRING_PROFILES_ACTIVE` | 部署期命令设为 `cli`（见下文）；服务本身不设 | 空 |
| `uam.session.idle` / `uam.session.absolute` / `uam.session.touch-interval` | 会话空闲 / 绝对时限 / 续期最小间隔 | `30m` / `12h` / `60s` |
| `uam.session.terminated-notice-ttl` | 管理员强制下线后，`verify` 的 401 仍带 `reason: terminated` 的时长（墓碑 `uam:term:{sha}` 的 TTL） | `10m` |
| `uam.login.throttle.max-failures` / `.block` | 登录失败限制 | `5` / `15m` |
| `uam.password.min-length` | 密码最短长度（上限固定 128 个码点，使任何合规密码都能在 256 个 UTF-16 字符的登录请求上限内提交） | `10` |

客户端超时（`application.yml`）：Redis 命令与连接 `spring.data.redis.timeout` / `connect-timeout` 各 1 s，PostgreSQL 取连接 `spring.datasource.hikari.connection-timeout` 1000 ms，均小于 Admin Center 调 UAM 的 1.5 s（`UAM_TIMEOUT_MS`）。

`uam.*` 可用 Spring 的任意外部化方式覆盖（环境变量写成 `UAM_SESSION_IDLE` 这类形式）。健康检查：`/actuator/health`（只暴露 UP / DOWN；就绪组含 PostgreSQL 与 Redis）；`/actuator/prometheus` 同样不校验调用方，只靠网络隔离。

## 调用方认证与接口约定

UAM **目前不识别调用方**，不校验任何服务凭据，只靠网络隔离（k8s NetworkPolicy、内网）保护：只有受信任的服务（Admin Center）才应能到达 UAM；服务身份（服务间认证）后续作为独立特性实现。因此**不要把 UAM 暴露到集群外或不受信任的网络**。

- **操作人头**：`POST /v1/auth/login`、`/v1/auth/sessions/verify`、`/v1/auth/logout` 不需要；`POST /v1/accounts/{accountId}/password`、`POST /v1/users`、`POST /v1/users/{userId}/disable`、`POST /v1/users/{userId}/enable`、`DELETE /v1/sessions/{sessionRef}` 必须带 `X-Operator-User-Id`、`X-Operator-Account-Id`、`X-Operator-Session-Ref`，缺失或非法 → 400 `operator_required`；各 `GET` 读接口可选带。UAM 信任这些值，只用于自我保护规则、`current` 标注与写操作日志，**不用于认证或授权**。
- **错误状态码（一个错误码对应一个状态码）**：`validation_failed` 一律 422（含请求体缺失 / 为空 / 过长字段、`Idempotency-Key`、`page=0` / `size>100` 等规则校验，带 `errors[{field, reason}]`）；无法解析或类型不符的请求（坏 JSON、空请求体、JSON 类型不对、非 UUID 的路径 id、`page=abc`）一律 400 `bad_request`（不回显请求值）；`operator_required` 仍为 400；改密时路径账号不是操作人自己的账号 → 403 `operator_forbidden`；`ErrorStatusContractIT` 遍历契约的每个接口固定这些映射。
- 写操作日志是事件 `uam.admin.write`（INFO，含操作人、对象、动作、结果）。

## 部署期命令（不是运维入口）

同一个 jar 以 `cli` profile 运行（`SPRING_PROFILES_ACTIVE=cli`）：不启动 Web、不连 Redis、先跑 Flyway、日志写标准错误。密码只经标准输入（一行），不接受命令行参数。退出码：0 成功；2 用法错误；其余见命令的标准错误。

```bash
# 以下两条需要数据库环境变量与 cli profile
printf '%s\n' "$INITIAL_PASSWORD" | SPRING_PROFILES_ACTIVE=cli java -jar apps/uam/target/uam.jar init-admin --account-name admin --user-name "系统管理员"   # 仅在没有任何用户时创建首个用户，幂等
printf '%s\n' "$PASSWORD" | SPRING_PROFILES_ACTIVE=cli java -jar apps/uam/target/uam.jar add-account --account-name-of admin --account-name admin2   # 给已有用户追加第二个账号；也可用 --user-id <UUID> 代替 --account-name-of
```

在容器里：`docker compose -f devops/local/docker-compose.stack.yml --env-file devops/local/e2e.env run --rm -T -e SPRING_PROFILES_ACTIVE=cli uam java -jar /app/uam.jar init-admin ...`（`devops/local/scripts/bootstrap.sh` 就是这样建首个用户的）。

## 本地栈、多实例与安全检查

- E2E 栈（PostgreSQL、Redis、UAM、Admin Center；UAM 不映射到宿主机）：`devops/local/scripts/bootstrap.sh up` / `down`。Playwright 用例见 [`apps/admin-center/AGENTS.md`](../admin-center/AGENTS.md)。
- 多实例栈（3 个 UAM + 3 个 Admin Center + nginx，`devops/local/docker-compose.scale.yml`）：`devops/local/scripts/scale-checks.sh up|check|down`（无粘滞与一致性断言）、`devops/local/scripts/rolling-restart.sh`（持续访问下滚动重启，统计意外登出与失败请求）。压测脚本与结果在 `devops/load/`（`k6/seed-data.sh` 造 10 万用户 / 1 万会话，`k6/list-and-detail.js`，`k6/validate-throughput.js`；SC-011 的判定方法见 `devops/load/SC-011.md`，**尚未测量**；已测结果见 `devops/load/RESULTS.md`）。
- k8s（清单在 `devops/k8s/`，kustomize：通用 `base/` + `components/` + `overlays/{kind,cloud}`，设计见 [`docs/system-design/system/README.md`](../../docs/system-design/system/README.md) §4.2.1）。**云上**：先建命名空间与 Secret（`uam-runtime`，契约见该节），复制 `devops/k8s/overlays/cloud` 改 host / 镜像标签 / ingress 类后 `kubectl apply -k`；数据库用托管服务，清单不含。**本地 kind**（集群 `cka`）：`devops/k8s/scripts/deploy.sh up|down|status|forward|test-account`（`test-account` 用部署期命令 `add-account` 给首个用户追加专用测试账号，随机密码写入 `~/.config/tech-toolkit/k8s-test-account-password`，0600，从不打印；它与首个用户权限相同，只用于本地，`down` 会一并删除；冒烟用例见 Admin Center 的 AGENTS.md）。`up` 构建两个镜像（`tech-toolkit/uam:local`、`tech-toolkit/admin-center:local`）并载入 kind，部署 PostgreSQL / Redis（StatefulSet + PVC）、UAM ×2、Admin Center ×2，入口是 Gateway API（Envoy Gateway v1.9.3，集群里没有时 `up` 自动按固定版本安装；GatewayClass 在 `devops/k8s/cluster/`），再用 `init-admin` 建首个用户；`forward` 把 Envoy 数据面转发到 `http://127.0.0.1:8088`（与多实例栈的 8088 冲突，不要同时跑；用 `EDGE_PORT` 改）。数据库密码由脚本首次部署时生成并只存在于 k8s Secret 里（重复 `up` 不换密码），首个用户的密码写入 `~/.config/tech-toolkit/k8s-admin-password`（0600）。UAM 只有 ClusterIP，NetworkPolicy（Calico）只放行 Admin Center → UAM → PostgreSQL / Redis 的调用链。
- 安全检查：`devops/local/scripts/security-check.sh [static|runtime|all]`（用法与检查项见 Admin Center 的 AGENTS.md）；其中对 UAM 日志的检查是「没有明文密码、`$argon2` 哈希前缀、会话标识」。

## 排障

- **启动失败**：PostgreSQL / Redis 连不上（就绪探针 `/actuator/health/readiness` 含二者）。
- **Admin Center 页面显示「服务暂不可用」**（用户不会被登出）：UAM 不可达或超过 `UAM_TIMEOUT_MS`；先查 UAM 就绪探针与 NetworkPolicy。修好后用户**无需重新登录**。
- **日志时间**：写操作日志的 `ts` 是 UTC（ISO-8601，带 `Z`）；`@timestamp` 跟随 JVM 时区。
- **`LoginTimingIT` 偶发失败**：它是统计性测试（24 个样本取中位数，容忍相对差 25%），机器繁忙时可能抖动；单独重跑该类，稳定失败才说明「账号不存在 / 密码错误 / 已停用」的耗时真的出现了可区分差别（不存在的账号必须也做一次 Argon2 比对）。
- **在线用户列表的规模上限**：`GET /v1/online-users` 在一次 Redis Lua 调用里扫描全部会话（约 1 万会话时 22 ms / 次），Redis 单线程执行，并发上限约 40 次 / 秒且与实例数无关，超过后会拖累所有实例的会话校验。数据与建议见 `devops/load/RESULTS.md` §4.1。
- **`mvn verify` 失败在 Testcontainers**：确认 Docker 在运行。
