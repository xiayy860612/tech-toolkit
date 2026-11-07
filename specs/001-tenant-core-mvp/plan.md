# Implementation Plan: Tenant Core MVP

**Branch**: `001-tenant-core-mvp` | **Date**: 2026-11-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-tenant-core-mvp/spec.md`

## Summary

在单个 cell 内落地 Tenant Core 的最小闭环：**CLI Bootstrap**（建 Tenant → 启用密码登录 → 建首个 User → 为其建 username/password Account → 订阅 `platform`），**platform web** username/password 登录（含首次强制改密），登录后左侧导航三个只读列表（Tenant、Module、User）。

技术路径（遵循 `docs/system-design/01-overview.md` 的已定栈与 D29 “UAM 与 Tenant 核心合并部署、package 边界保留”）：

- 后端 `backend/`：Java 21 + Spring Boot 的 Maven 多子项目——共享库 `core-lib`（UAM 与 Tenant 核心两个 package 边界、Audit）、REST 服务 `tenant-core-api`、Bootstrap CLI `tenant-core-cli`（与 API 共用 `core-lib`，直连数据库，符合 D23“受控环境运行”）。
- 前端 `apps/platform-web/`：Next.js（SSR，App Router），服务端充当 Platform BFF：session 存 Redis，浏览器只有 HttpOnly cookie；Token 不到浏览器。
- 存储：PostgreSQL（单一 schema，表按 package 前缀 `core_` / `uam_` / `audit_`）+ Redis（BFF session）。

### 相对设计基线的 MVP 调整（已于 2026-11-07 确认；实现前先回写 `docs/system-design/`，见 tasks T007）

| 基线 | MVP 做法 | 原因 / 回补时机 |
|---|---|---|
| D18：网关 ext_authz 换发 Internal JWT，服务本地验签 | 不部署网关。`tenant-core-api` 的 UAM**同时**支持校验 Opaque Token（查表）与 Internal JWT（本地验签），并提供换发接口与 JWKS；BFF 目前直接带 Opaque Token 调用 | 引入网关时只需让 ext_authz 调换发接口，下游与 UAM 不改 |
| D29：UAM 与档案分 schema、分数据库角色 | **只分 Java 包与表名前缀，不分 schema、不拆 package 角色**；package 间不建外键、不联表；仅 `audit_event` 对应用角色撤销 UPDATE/DELETE | 以后拆服务时按前缀把对应表从库中迁出 |
| tenant_user 档案（Tenant Core） | 暂不建档案表，User 列表读 `uam_user`（已确认） | YAGNI；引入 RBAC 时再建，经“User 已创建”事件同步 |
| Tenant 注册表归 Platform Service | CLI 直接写 `core_tenant` / `core_module_subscription`（已确认） | Platform Service 不在本期；对账为 06 待定项 |

### 命名约定（Ubiquitous Language）

文档中的专业术语与业务术语使用英文，术语表（Term 与含义）见 [spec.md](spec.md) 的 “Ubiquitous Language”。**代码、表名、API、CLI、测试名都用同一个词，不另造同义词**，下表是各术语对应的代码命名；评审以 spec 术语表和本表为准。

代码层面的划分不叫 Module（Module 只指产品模块）：Java 的 `tenant`、`uam`、`audit` 称 package，Maven 的 `core-lib`、`tenant-core-api`、`tenant-core-cli` 称子项目，认证部分直接称 UAM。

| Term | 代码中的命名 |
|---|---|
| Tenant | `Tenant`、`tenantId`、`core_tenant`、`/v1/tenants`、`tenant create`；`tenant show` 的输出为 `TenantDetail` |
| Tenant Slug | `tenantSlug`、`slug`、`--tenant <slug>` |
| User | `User`、`tenantUserId`（`tu_…`，对外 User ID）、`uam_user`、`/v1/users`；内部主键只叫 `id`，外键 `user_id`，不作为对外字段名 |
| Display Name | `displayName`、`display_name`、`--display-name` |
| Account / username | `Account`、`uam_account`、`account create`；`username`（Account 的 `subject`）、`--username` |
| Login Method | `LoginMethod`、`uam_tenant_login_method`、`login-method enable` |
| Credential | `PasswordCredential`、`uam_credential_password` |
| Module / Module Subscription | `moduleCode`、`module_code`；`ModuleSubscription`、`core_module_subscription`、`subscription add`、`/v1/module-subscriptions` |
| Opaque Token | `OpaqueToken`、`uam_opaque_token`，经请求头 `X-Session-Id` 携带；前缀 `tk_` 仅用于泄漏扫描 |
| Internal JWT / Token Exchange | `InternalJwt`、`InternalJwtService`，经 `Authorization: Bearer` 携带；`TokenExchange*`、`/v1/auth/exchange`、审计 `token.exchange` |
| Session | `Session`、`sid`（`s_…`，非机密的会话标识，写入 Internal JWT，与 `X-Session-Id` 里的机密值不同）；BFF 在 Redis 保存 Session，浏览器只持 cookie |
| Audit Event | `AuditEvent`、`audit_event` |
| Bootstrap | `tenant-core-cli`（Bootstrap CLI） |
| Platform / UAM | `platform-web`、`platform`；`uam` package、`uam_` 表前缀 |

### 技术栈变更（2026-11-07，用户决定；实现前需确认）

| 范围 | 变更 | 影响 |
|---|---|---|
| 后端 | Spring Boot 3.5.7 → **4.1.2**（Spring Framework 7、Spring Security 7.1、Jackson 3、Flyway 12、JUnit 6、Testcontainers 2.0.5）。Java 21 不变 | 起步依赖改名（`spring-boot-starter-web` → `spring-boot-starter-webmvc`）；Testcontainers 2 的坐标与包名变化（`testcontainers-postgresql`、`testcontainers-junit-jupiter`、`org.testcontainers.postgresql.PostgreSQLContainer`）；`TestRestTemplate` 移到 `spring-boot-resttestclient`；测试里的 `ObjectMapper` 改用 Jackson 3（`tools.jackson.*`）。**行为必须保持不变**：现有测试与 API 契约是回归依据 |
| 前端 | 引入 **shadcn/ui**（组件源码复制进仓库，基于 Radix UI）+ **Tailwind CSS 4** + `lucide-react`、`class-variance-authority`、`clsx`、`tailwind-merge`。Next.js 保持 15.5，不升 16 | 取代手写的 `globals.css`；生成的 `src/components/ui/**` 作为第三方生成代码排除出覆盖率统计（在 AGENTS.md 说明）；jsdom 测试需补 `matchMedia`、`ResizeObserver` 的 polyfill；shadcn CLI 需联网拉取组件 |
| 界面 | FR-024、FR-025：自适应与全中文文案 | 窄屏顶部栏 + Sheet 折叠菜单；列表 `md` 以上用 Table，以下用卡片；时间在客户端按本地时区渲染（避免 hydration 不一致） |
| 界面骨架 | FR-026、FR-027、FR-028：顶栏、用户菜单、统一页头 | 新增 `TopBar`（产品名 + `UserMenu`，shadcn `DropdownMenu`）与 `PageHeader`（标题 + `actions` 插槽）；`AppShell` 改为 顶栏 + 侧边栏（仅三项）+ 内容区（`max-w` 容器）；窄屏的菜单按钮并入顶栏最左；退出登录只在用户菜单（POST 表单）。用户菜单所需的 Display Name、username、Tenant Slug 由 `(console)/layout.tsx` 服务端经 `GET /v1/auth/me` 取得并作为 props 传入，Opaque Token 仍不进入客户端 |
| 数据库 | PostgreSQL 16 → **18**（官方镜像 `postgres:18`，当前 18.1） | 18 的镜像数据目录改为 `/var/lib/postgresql/18/docker`，Compose 的数据卷必须挂 `/var/lib/postgresql`（不是 `/var/lib/postgresql/data`）；Testcontainers 镜像改为 `postgres:18`；Flyway 12 与 pgjdbc 42.7 均支持 |
| 测试 | 增加 **ArchUnit** 1.5.1，使用官方的 JUnit 6 模块 `archunit-junit6`（1.5.0 起提供，见 ArchUnit Release Notes），用 `@AnalyzeClasses` 与 `@ArchTest` 声明规则 | 把 package 边界、命名约定（Ubiquitous Language）、不泄漏秘密写成可执行的架构规则，见 research R18 |

## Technical Context

**Language/Version**: Java 21（后端、CLI）；TypeScript 5 / Node.js 22 LTS（前端）

**Primary Dependencies**: Spring Boot 4.1（Web MVC、JDBC、Actuator；Spring Framework 7、Jackson 3）、Flyway、Spring Security Crypto（Argon2id）、Spring Security OAuth2 JOSE / Nimbus（Internal JWT 签发与验签）、picocli（CLI）；Next.js 15 + React 19 + Tailwind CSS 4 + shadcn/ui（Radix UI、lucide-react）、ioredis；Testcontainers

**Storage**: PostgreSQL 18（业务与 Audit 数据）；Redis 7（BFF session）

**Testing**: ArchUnit（架构规则）；JUnit 5 + AssertJ + Testcontainers（后端单元/集成/契约）；Vitest + React Testing Library（前端单元/组件）；Playwright（E2E，仅关键旅程）；覆盖率 JaCoCo / Vitest coverage，行覆盖率 ≥ 90%

**Target Platform**: Linux 容器（Kubernetes 为目标；MVP 本地用 Docker Compose）；现代桌面浏览器

**Project Type**: web-service（后端 + CLI）+ web-application（SSR 前端）

**Performance Goals**: 三个列表页在 500 条数据下首屏 ≤ 2 s（SC-008）；登录往返 ≤ 1 s（本地、单实例）

**Constraints**: 密码 Argon2id 且永不明文落盘/落日志；CLI 密码不走命令行参数；BFF 与 `tenant-core-api` 均无状态、可水平扩展；Token 库内只存哈希；所有 CLI 操作幂等

**Scale/Scope**: Tenant ≤ 5（前期上限），User 数十到数百；3 个只读页 + 登录页 + 改密页；5 个 CLI 写命令（建 Tenant、启用 Login Method、建 User、建 Account、订阅 Module）+ 1 个只读 `show`；API 另有 Token 换发与 JWKS

## Constitution Check

`.specify/memory/constitution.md` 仍是未填的模板；仓库约定以根 `AGENTS.md` 为准（见 User 记忆“宪章即 AGENTS.md”），门禁逐项对照如下：

| 门禁（根 AGENTS.md） | 结论 | 说明 |
|---|---|---|
| 规格先于实现 | 通过 | 先 spec/clarify，后 plan；偏离基线的 4 项已确认，并在实现前先回写设计文档（tasks T007） |
| 测试先行（red → green → refactor） | 通过 | tasks 阶段每个用户故事先列失败测试任务 |
| 单元测试覆盖率 ≥ 90%（行） | 通过 | 后端 JaCoCo、前端 Vitest 均设 90% 阈值；排除路径仅限启动类与生成代码 |
| 契约优先 | 通过 | `contracts/` 先于实现；契约测试先红 |
| 安全与秘密 | 通过 | 无密钥入库；`.env.example` 与实际配置分离；日志脱敏 |
| 子项目 `AGENTS.md` / `CLAUDE.md` | 待办 | 为 `backend/`、`apps/platform-web/` 各建一份（tasks 第一阶段） |
| 设计原则 SOLID / YAGNI / KISS | 通过 | Login Method 走 `Authenticator` 接口但只实现密码；不做 RBAC、档案、网关 |

无需豁免，Complexity Tracking 留空。设计后复核：通过（见 Phase 1 产物）。

## Project Structure

### Documentation (this feature)

```text
specs/001-tenant-core-mvp/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── cli.md                 # CLI 命令契约
│   └── tenant-core-api.yaml   # BFF → tenant-core-api 的 OpenAPI
└── tasks.md                   # /speckit-tasks 生成
```

### Source Code (repository root)

```text
backend/                          # Maven 多子项目（+ AGENTS.md / CLAUDE.md）
├── pom.xml
├── core-lib/
│   ├── src/main/java/tech/toolkit/tenantcore/
│   │   ├── tenant/               # Tenant Core 的 `tenant` package：Tenant、Module Subscription
│   │   ├── uam/                  # UAM：User、Account、Login Method、Credential、Opaque Token、Internal JWT（签发/验签/JWKS）、锁定
│   │   ├── audit/                # Audit 写入
│   │   └── common/               # 错误码、时钟、ID 生成
│   └── src/main/resources/db/migration/   # Flyway：core / uam / audit
├── tenant-core-api/              # REST：auth 与三个只读列表
└── tenant-core-cli/              # picocli Bootstrap 命令

apps/platform-web/                # Next.js SSR（+ AGENTS.md / CLAUDE.md）
├── src/app/                      # login、change-password、(console)/tenants|modules|users
├── src/server/                   # session(Redis)、tenant-core 客户端
└── tests/                        # unit、component、e2e

deploy/
└── docker-compose.yml            # 本地 Postgres + Redis
```

**Structure Decision**: 后端按 D29 单库单服务（单 schema，表带 package 前缀）但两个 package（`tenant`、`uam`）分包，package 间只通过接口调用，不互读对方的表；CLI 与 API 共用 `core-lib` 以保证业务规则（幂等、密码策略、Audit）只有一份。前端放 `apps/` 以便后期并列加入 tenant-web。

## Complexity Tracking

无违规项。
