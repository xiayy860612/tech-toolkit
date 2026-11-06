# AGENTS — backend

后端子项目。**先读仓库根目录 [`AGENTS.md`](../AGENTS.md)**（测试先行、覆盖率、最小改动），再读本文件。规格在 [`specs/001-tenant-core-mvp/`](../specs/001-tenant-core-mvp/)，架构在 [`docs/system-design/`](../docs/system-design/)。

## 技术栈与目录

Java 21、Spring Boot 3.5、Maven 多模块、PostgreSQL 16（Flyway）、Testcontainers、picocli。

```text
backend/
├── pom.xml               # 父工程：依赖版本、JaCoCo、Spotless
├── core-lib/             # tenant / uam / audit 三个 package + Flyway 迁移，被 API 与 CLI 共用
├── tenant-core-api/      # REST：auth、token exchange、JWKS、三个只读列表
└── tenant-core-cli/      # Bootstrap CLI（picocli），直连数据库
```

- 代码里的 Maven 单元叫“子项目”，Java 的 `tenant`、`uam`、`audit` 叫 package；**Module 只指可订阅的产品模块**（如 `platform`）。
- 数据库：单一 schema `public`，表名前缀 `core_` / `uam_` / `audit_`；package 之间不建外键、不联表（见 `specs/001-tenant-core-mvp/data-model.md`）。

## 命名规则（Ubiquitous Language）

类名、方法、变量、表名、字段、API、CLI 与测试名使用同一个英文业务术语：Tenant、Tenant Slug（`tenantSlug`）、User（对外 `tenantUserId`）、Display Name、Account、username、Login Method、Credential（`PasswordCredential`）、Module Subscription、Opaque Token、Internal JWT、Token Exchange、Session、Audit Event、Bootstrap、UAM。术语表见 `specs/001-tenant-core-mvp/spec.md`，代码命名映射见 `plan.md`「命名约定」。不另造同义词；新增业务概念先登记术语表。

## 命令

需要 Docker（Testcontainers 会自动拉起 PostgreSQL）。

```bash
cd backend
mvn spotless:apply        # 格式化（google-java-format）
mvn test                  # 单元测试 + 集成测试（*Test 与 *IT）
mvn verify                # 测试 + 覆盖率门禁（行覆盖率 ≥ 90%）+ 格式检查
mvn -pl tenant-core-api -am spring-boot:run   # 启动 API（需 .env 对应的环境变量，dev profile）
mvn -pl tenant-core-cli -am package -DskipTests   # 打包 CLI → tenant-core-cli/target/tenant-core-cli.jar
```

CLI 调用方式：`tenant-core-cli/bin/tenant-core-cli <命令>`（读取环境变量；契约见 `specs/001-tenant-core-mvp/contracts/cli.md`）。

## 测试与覆盖率

- 先写失败的测试，再实现（red → green → refactor）。单元测试用 `*Test`，Testcontainers 集成/契约测试用 `*IT`，二者都在 `mvn test` 中运行。
- 覆盖率口径：**行覆盖率**，JaCoCo，每个子项目 BUNDLE ≥ 90%（`mvn verify` 校验；CI 同）。
- 经认可的排除路径：`**/*Application.class`、`**/TenantCoreCliMain.class`（仅进程入口，行为都在被测类里）。其余排除须在 PR 说明理由。
- 集成测试基类 `PostgresTestBase`（在 `core-lib` 的 test-jar 中）启动 PostgreSQL 容器、执行迁移并在每个测试前清库。

## 环境变量

见 [`.env.example`](.env.example)：数据库（owner 用于迁移，app 用于运行）、`UAM_ISSUER`、`UAM_AUDIENCE`、`UAM_CELL_ID`、`UAM_JWT_TTL_SECONDS`、`UAM_SIGNING_KEY_PEM`。密钥只来自环境变量或挂载的密钥文件，**不入库**；`dev` profile 才允许临时签名密钥。日志与审计不得出现密码、哈希、令牌。
