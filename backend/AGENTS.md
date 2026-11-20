# AGENTS（backend）

Tenant Admin Center 后端，即平台分层中的 **UAM**（租户级业务服务）：User、Account、认证与会话、审计。
规格与设计见 `specs/001-tenant-admin-center/`；根 `AGENTS.md` 的原则（测试先行、覆盖率门禁、契约优先、最小改动）在此同样适用。

## 技术栈与目录

- Java 21 · Spring Boot 4.1.2 · Maven **单模块** · PostgreSQL 16 · Flyway · Bouncy Castle（Argon2id）· nimbus-jose-jwt（校验内部 JWT）
- 包根 `tech.toolkit.tenantcore`，按业务能力分包：`identity/`（User、Account、凭证）、`auth/`（登录、会话、锁定）、`audit/`、`platform/`（租户上下文、JWT 校验、密码）、`api/`（仅 Web 层）、`bootstrap/`（首位管理员预置）
- 迁移脚本：`src/main/resources/db/migration`；测试：`*Test`（单元）与 `*IT`（Testcontainers 集成），均在 `mvn test` 运行
- **后端仅内网可达，只接受 BFF 签发的内部 JWT**；会话在本服务（`session` 表），租户由 JWT 的 `tenant_id` 决定，本期恒为 0

## 命令

| 目的 | 命令 |
|---|---|
| 本地依赖（PostgreSQL、Redis） | `docker compose up -d` |
| 编译 | `mvn -q -DskipTests compile` |
| 测试（含集成测试，需要 Docker） | `mvn test` |
| 全部门禁（格式、测试、覆盖率） | `mvn verify` |
| 自动格式化 | `mvn spotless:apply` |
| 运行（开发） | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` |
| 预置首位管理员 | `mvn spring-boot:run -Dspring-boot.run.arguments="--bootstrap --username=<name>"` |
| 性能类测试（默认不跑） | `mvn test -DexcludedTestGroups=none -Dgroups=performance` |

## 质量门禁

- 行覆盖率 **≥ 90%**（JaCoCo，`verify` 阶段检查）；排除路径仅 `**/*Application.class`
- Spotless（google-java-format）在 `verify` 阶段检查
- 行为变更先写会失败的测试（红 → 绿 → 重构）

## 环境变量

见 `.env.example`。关键项：数据库连接、锁定阈值、会话空闲/绝对超时与保留期、内部 JWT 的 issuer/audience/公钥路径/时钟偏差。**密钥与私钥不入库**；开发密钥由 `frontend/scripts/gen-internal-jwt-keys.mjs` 生成到被忽略的 `.keys/`。

## 约定

- 授权所需的角色一律读数据库，不信任令牌声明；租户上下文只来自已验签的 JWT
- 日志与审计 `detail` 永不含密码、会话令牌、JWT
- Argon2id 参数在此记录（见下），由 `PasswordHasherBenchmarkTest` 校准，目标单次哈希 100–400ms：内存 48 MiB（49152 KiB）、迭代 4、并行度 1（默认值，可由配置覆盖；在开发机上约 100–150ms，换机型后需重新校准）
