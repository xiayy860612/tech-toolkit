# AGENTS — apps/uam（UAM：用户、账号、会话的唯一权威）

遵循仓库根 [`AGENTS.md`](../../AGENTS.md)；本文件补充 UAM 的技术栈、命令与门禁。规格见 [`specs/002-admin-center-uam/`](../../specs/002-admin-center-uam/)，权威契约见 [`contracts/uam/openapi.yaml`](../../contracts/uam/openapi.yaml)。

## 技术栈与目录

- Java 21、Spring Boot 4.0.x（Web MVC、Validation、JDBC、Data Redis（Lettuce）、Actuator、Flyway 社区版）、`spring-security-crypto` + BouncyCastle（Argon2id）、PostgreSQL 16、Redis 7。
- 根包 `com.s2u2m.uam`，分层 `api → application → domain ← infrastructure`（`domain` 不依赖任何框架，由 ArchUnit 守住）：
  - `api/`：REST 控制器、DTO、`problem+json` 错误映射、客户端认证过滤器、操作人上下文解析
  - `application/`：用例（登录、校验会话、退出、用户查询 / 新建 / 停用 / 启用、在线用户、会话终止、改密）与 `AccountProvisioningService`
  - `domain/`：`model`、`policy`、`port`
  - `infrastructure/`：JDBC 仓储、Redis 会话存储与 Lua 脚本、Argon2id、客户端注册表、写操作日志
  - `cli/`：部署期一次性命令 `init-admin`、`add-account`、`generate-service-key`（不是运维入口）
- 资源：`src/main/resources/{application.yml,logback-spring.xml,db/migration/,redis/}`。
- UAM **不使用任何用户凭据**；每个接口先校验客户端的服务密钥（`Authorization: Bearer`），操作人由客户端以 `X-Operator-*` 请求头传入（见 research R4、R5）。

## 命令（在仓库根目录执行）

```bash
# lint：格式检查 / 自动格式化（Spotless + google-java-format）
mvn -f apps/uam/pom.xml spotless:check
mvn -f apps/uam/pom.xml spotless:apply

# typecheck：编译
mvn -f apps/uam/pom.xml -DskipTests compile

# 单元测试（*Test）
mvn -f apps/uam/pom.xml test

# 完整门禁：单元 + 集成 / 契约 / 并发测试（*IT，需要本机 Docker）+ JaCoCo 行覆盖率检查 + 打包
mvn -f apps/uam/pom.xml verify

# 本地运行（先在 infra/local 启动 PostgreSQL 与 Redis）
docker compose -f infra/local/docker-compose.yml --env-file infra/local/.env up -d
mvn -f apps/uam/pom.xml spring-boot:run
```

## 测试与覆盖率

- 测试先行（red → green → refactor）：新行为或缺陷修复先写会失败的测试。
- 单元测试 JUnit 5 + AssertJ + Mockito；集成 / 并发测试（`*IT`）用 Testcontainers 的真实 PostgreSQL 16 与 Redis 7；契约测试把响应按 `contracts/uam/openapi.yaml` 校验；ArchUnit 守分层。
- **覆盖率口径：行覆盖率（LINE）≥ 90%**，工具 JaCoCo，单元与集成测试合并统计；`mvn verify` 在低于阈值时失败，CI 同样执行。
- **排除路径**（只允许放不含业务逻辑的代码，终审在 PR 评审）：`**/UamApplication.class`（启动类，只委托）、`**/config/*Config.class`（纯配置类）、`**/generated/**`（生成代码）。

## 环境变量与示例配置

真实值只通过部署环境注入，禁止入库；示例见 `infra/local/.env.example`。

| 变量 | 说明 | 默认 |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL 连接 | 必填 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | Redis 连接 | 必填 |
| `UAM_CLIENTS` | 客户端清单（JSON：`id`、`keyHashes`（1–2 个 `sha256:<hex>`）、`permissions`） | 必填 |
| `uam.session.idle` / `uam.session.absolute` | 会话空闲 / 绝对时限 | `30m` / `12h` |
| `uam.login.throttle.max-failures` / `.block` | 登录失败限制 | `5` / `15m` |
| `uam.password.min-length` | 密码最短长度 | `10` |

## 部署期命令（不是运维入口）

同一个 jar 以 `cli` profile 运行：不启动 Web、不连 Redis、先跑 Flyway、日志写标准错误。

```bash
java -jar apps/uam/target/uam.jar generate-service-key --client admin-center   # 密钥只在标准输出出现一次
printf '%s\n' "$INITIAL_PASSWORD" | java -jar apps/uam/target/uam.jar init-admin --account-name admin --user-name "系统管理员"
```
