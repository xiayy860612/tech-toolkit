# AGENTS — auth

OAuth 2.0 / OIDC 授权服务器（UAM），基于 Spring Authorization Server。**先读仓库根目录 [`AGENTS.md`](../AGENTS.md)**（测试先行、覆盖率、最小改动），再读本文件。规格在 [`specs/001-uam-oauth2-sas/`](../specs/001-uam-oauth2-sas/)：`spec.md`（需求）、`plan.md`（设计）、`tasks.md`（任务）、`contracts/`（接口契约）。

## 技术栈与目录

Java 21、Spring Boot 4.1（Spring Security 7，SAS 已并入 Security 7）、Maven 单模块、PostgreSQL 18（Flyway）、Spring Session JDBC、Thymeleaf、Testcontainers、ArchUnit、JaCoCo、Spotless。

```text
auth/
├── pom.xml
├── docker-compose.yml          # 本地 PostgreSQL（含 app 角色初始化脚本）
├── src/main/java/tech/toolkit/auth/
│   ├── account/                # 用户、密码策略、锁定、改密
│   ├── client/                 # client 登记与扩展字段
│   ├── oauth/                  # SAS 配置、令牌定制、重用检测、JWK 源、吊销
│   ├── session/                # SSO 会话、绝对期过滤器
│   ├── logout/                 # 退出、back-channel 投递
│   ├── admin/                  # 管理 API
│   ├── audit/                  # 审计事件
│   ├── cleanup/                # 过期数据清理
│   ├── bootstrap/              # 首个管理员的一次性命令
│   └── config/
├── src/main/resources/db/migration/   # Flyway（owner 角色执行）
└── src/test/…                  # *Test 单元；*IT Testcontainers 集成与契约
```

- 数据库表名前缀 `auth_`；`oauth2_*`、`spring_session*` 沿用框架标准结构。详见 `specs/001-uam-oauth2-sas/data-model.md`。
- 设计与实现遵循主流标准（RFC 9700、RFC 9068、OIDC、OWASP、NIST 800-63B），见 `plan.md`「设计与实现的参考基准」；框架没有现成支持的部分，在代码注释里写明依据的条款。
- MVP 只做 auth；auth-web（Portal BFF）是后续特性，不在本目录。

## 命令

需要 Docker（Testcontainers 自动拉起 PostgreSQL）。

```bash
cd auth
mvn spotless:apply          # 格式化（google-java-format）
mvn test                    # 单元 + 集成 + 契约测试（*Test 与 *IT）
mvn verify                  # 测试 + 覆盖率门禁（行覆盖率 ≥ 90%）+ 格式检查
mvn test -Djacoco.skip=true -Dtest.excluded.groups=none -Dtest=LoginLoadIT   # 性能测试（SC-004），须关闭 JaCoCo
docker compose up -d postgres
mvn spring-boot:run -Dspring-boot.run.profiles=dev          # 启动（dev profile）
mvn spring-boot:run -Dspring-boot.run.profiles=bootstrap    # 一次性：创建首个管理员与 auth-admin client
```

## 测试与覆盖率

- 先写失败的测试，再实现（red → green → refactor）；tasks.md 每个 story 阶段都先列测试任务。
- 覆盖率口径：**行覆盖率**，JaCoCo，BUNDLE ≥ 90%（`mvn verify` 校验；CI 同）。
- 经认可的排除路径：仅 `**/AuthApplication.class`（进程入口，行为都在被测类里）。其余排除须在 PR 说明理由。
- 架构测试（ArchUnit）：`architecture/ArchitectureTest`（package 无环、禁用字段注入与标准流、含密钥字段的类必须自带 `toString()`）。
- 集成测试基类 `support/PostgresTestBase` 启动 PostgreSQL 容器、用 owner 角色迁移、以受限 app 角色运行，并在每个测试前清库。

- 性能测试标记为 `@Tag("performance")`，默认不跑；500 并发登录须在关闭 JaCoCo 下测（插桩会让 argon2 慢数倍）。
- 过期数据清理 `cleanup/ExpiredDataCleanupJob` 由 `auth.cleanup.*` 配置，多实例靠 PostgreSQL advisory lock 只让一个实例执行，从不清理审计表。

## 环境变量

见 [`.env.example`](.env.example)：数据库（owner 用于迁移，app 用于运行）、`AUTH_ISSUER`、`AUTH_SIGNING_KEYS`、`AUTH_ADMIN_REDIRECT_URIS`。密钥只来自环境变量或挂载文件，**不入库、不入镜像、不入日志**；只有 `dev` profile 允许临时签名密钥。日志与审计不得出现密码、哈希、令牌。

## 密钥轮换

1. 生成新的 ES256 PEM，把它加入 `AUTH_SIGNING_KEYS`，**放在当前密钥之后**（仅验证），滚动重启，JWKS 同时发布新旧公钥。
2. 等下游缓存刷新后，把新密钥调到第一位（成为签名密钥），再次滚动重启。
3. 旧公钥至少保留一个访问令牌有效期（默认 10 分钟）后再移除。

下游服务应缓存 JWKS，遇到未知 `kid` 时重新拉取一次再判定（测试用的实现见 `support/TokenVerifier`，全过程见 `oauth/KeyRotationIT`）。签名只用第一把密钥（`JwkSourceConfig.jwtEncoder`），其余密钥只出现在 JWKS 里。
