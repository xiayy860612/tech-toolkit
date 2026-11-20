# Implementation Plan: Tenant Admin Center

**Branch**: `001-tenant-admin-center` | **Date**: 2026-11-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-tenant-admin-center/spec.md`

## Summary

为多租户 SaaS 提供 **Tenant Admin Center**：用户在登录页以用户名密码登录后进入后台，管理 User（每个 User 必然有用户名与密码）及日后的其他登录方式 Account（手机号、微信、OAuth2.0，每种类型至多一个、各用自己的表）与登录会话。**本期不涉及租户管理，所有用户默认 `tenant-id = 0`**，但多租户的数据隔离能力照常实现并验证。

技术方案（详见 [research.md](research.md)）：

- **前后端分离，两个独立子项目**：`backend/`（Java 21 + Spring Boot 4.1，Maven 单模块，REST）与 `frontend/`（Next.js 15 + React 19 + TypeScript，作为独立 Node 服务部署），后端与 BFF 之间仅通过 [contracts/openapi.yaml](contracts/openapi.yaml) 交互；BFF 自有的浏览器侧接口见 [contracts/bff-openapi.yaml](contracts/bff-openapi.yaml)（契约优先）。
- **拓扑**：`浏览器 ──会话令牌──▶ BFF（frontend/，对外）──内部 JWT──▶ 后端（backend/，仅内网；会话在此）──▶ PostgreSQL`；BFF 另连 Redis（仅限流）。后端永不直接面向浏览器；后期多个 BFF 复用同一套会话。
- **身份模型**：用户名密码**内置于 `User`**（`app_user` 直接持有 username、密码哈希、失败锁定；每个 User 必然有）；`Account` 只表示其他登录方式，每种类型一张自己的表、以 `user_id` 关联 User 且每类型至多一个（首期没有此类表）。新增登录方式不动 User（SC-007）。主键：user、审计为 bigint 自增，会话为 UUID（FR-004a）。
- **多租户**：共享库共享 schema，所有业务表带 `tenant_id`；应用层强制租户上下文 + PostgreSQL Row-Level Security 兜底；租户上下文**只来自已验签的内部 JWT**（FR-025）。
- **会话与内部凭证**：会话由**后端统一维护**（`session` 表，令牌只存哈希，空闲 30 分钟 / 绝对 8 小时，撤销在后端同事务完成）；浏览器 ↔ **BFF（Next.js）** 只持后端签发的不透明会话令牌（HttpOnly Cookie）；BFF 每次请求先让后端解析会话（短 TTL 内存缓存），再签发短期**内部 JWT**（EdDSA，含当前用户、当前租户与会话 id `sid`）调用后端，后端每次按 `sid` 校验会话有效（FR-010、FR-024a）。限流在 BFF（Redis）。管理员在独立的「会话管理」页查看并强制下线本租户的登录会话（后端接口，见 research R18）。
- **界面**：依据用户提供的 Design 参考稿（登录 / 总览 / 列表 / 表单四个画板）落地，并按 spec 范围做裁剪，见 [contracts/ui-design.md](contracts/ui-design.md)。

## 架构定位（平台分层）

依据平台分层架构图（入口层 / 租户级业务服务 / 平台服务 / 基础服务 + Event Bus）：

| 层 | 组件 | 租户隔离 | 与本特性的关系 |
|---|---|---|---|
| 入口 | Tenant Admin BFF、Biz BFF、Platform BFF、API Gateway | — | **本特性的 `frontend/` 即 Tenant Admin BFF**（含管理界面），只调用 UAM |
| 租户级业务服务 | **UAM** | **必须** | **本特性的 `backend/` 即 UAM**：User（含用户名密码）、认证、会话；全部数据按租户隔离（RLS + 内部 JWT 租户） |
| 平台服务 | Tenant Manager、App Manager | **不做**（不区分租户） | 租户的创建、状态、确定方式归 Tenant Manager；**本特性不涉及租户管理**，本期租户固定为 `tenant-id = 0`，UAM 内**没有 tenant 表**，仅以各表的 `tenant_id` 列标识 |
| 基础服务 | Audit、Scheduler、Notification | **必须** | 首期审计内置于 UAM（`AuditSink` 端口，写本库 `audit_log`），后续可替换为发布到 Audit 服务；Notification 用于未来的临时密码送达，首期不做 |

**隔离规则（项目级）**：除平台服务外，所有业务服务与基础服务都必须做租户隔离。落到本特性：所有表带 `tenant_id` 并启用 RLS（见 data-model.md）；本期没有 tenant 表（租户管理不在范围内）。

## Technical Context

**Language/Version**: 后端 Java 21（本机已有 OpenJDK 21）；前端 TypeScript 5.x，Node.js 22 LTS

**Primary Dependencies**: 后端 Spring Boot 4.1.2（Web、Security、Data JPA、Validation）、Flyway、Bouncy Castle（`bcprov-jdk18on`，Argon2id 所需）、OpenAPI 校验；参考了用户提供的后端 Maven 清单，但**不使用多模块**（单个 Maven 模块），采用 `nimbus-jose-jwt`（校验 BFF 签发的内部 JWT），**未采用** `picocli`（`bootstrap` 以同一应用的非 Web 运行模式实现）；前端 Next.js 15（App Router）、React 19、Tailwind CSS 4（`@theme` 承载设计稿 token）、radix-ui + class-variance-authority + clsx + tailwind-merge + lucide-react + tw-animate-css（shadcn 风格组件）、zod（表单校验，规则与后端一致）、openapi-typescript（由契约生成类型）；包管理 pnpm，Node ≥ 22。参考了用户提供的前端依赖清单，采用 `ioredis`（BFF 限流计数存储，测试用 `ioredis-mock`）与 `jose`（签发内部 JWT）

**Storage**: PostgreSQL 16（业务数据，含 `session` 表；开发用 Docker Compose，测试用 Testcontainers）；Flyway 管理迁移；Redis 7（仅 BFF 的限流计数，开发用 Docker Compose）

**Testing**: 后端 JUnit 5 + Spring Boot Test + Testcontainers（单元测试 `*Test`、集成测试 `*IT` 均在 `mvn test` 运行）+ ArchUnit（分层/依赖约束）+ 契约校验；JaCoCo 行覆盖率 ≥ 90%（`verify` 阶段 check）；Spotless（google-java-format）在 `verify` 阶段检查；前端 Vitest + Testing Library + jest-dom + jsdom（覆盖率 `@vitest/coverage-v8`），E2E 用 Playwright；lint = ESLint（next 配置）+ Prettier，另有 `typecheck`；先写失败测试（红→绿→重构）

**Target Platform**: Linux 容器部署（后端容器仅内网可达；Next.js BFF standalone 容器为唯一对外入口；PostgreSQL；Redis）；桌面浏览器（现代 Chromium / Firefox / Safari）

**Project Type**: web-application（`backend/` + `frontend/`）

**Performance Goals**: 满足 SC-005：1,000 User/租户、100 并发管理员会话下，列表与搜索 p95 ≤ 2 秒；登录 p95 ≤ 1 秒（Argon2id 参数在此预算内调优，以 `PasswordHasherBenchmarkTest` 校准）

**Constraints**: 租户隔离 0 泄露（SC-003）；本期租户固定为 `tenant-id = 0`（BFF 环境变量 `DEFAULT_TENANT_ID`），但隔离、RLS、JWT 携带租户都照常实现；后端不直接面向浏览器，只接受 BFF 签发的内部 JWT（FR-024a）；登录对"用户不存在/账号停用/被锁定/凭证错误"响应不可区分（FR-007、US1 场景 2/5）；密码与会话令牌永不入日志；前端不得依赖未入契约的后端行为（FR-024）

**Scale/Scope**: 首期 1 个后端服务、1 个前端应用；约 7 个页面（登录、强制改密、用户列表、用户新建/编辑、用户详情含登录账号区、会话管理、无权限页）；后端约 17 个接口（BFF 仅有登录/登出 2 个自有路由，其余为代理）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

> `.specify/memory/constitution.md` 仍是未填写的模板；按仓库约定，宪章即根 `AGENTS.md`，门禁以其为准。

| 门禁（来源：根 AGENTS.md） | 结论 | 说明 |
|---|---|---|
| 范围与最小改动 | ✅ | 仅新增 `backend/`、`frontend/` 与本 feature 文档，不触及既有文件 |
| 规格先于实现变更 | ✅（待确认） | 需求/设计变更（BFF 与内部 JWT、会话管理页、默认租户 0、角色以数据库为准）均已先更新 spec/plan/tasks，待用户确认后再实现；设计稿与 spec 的差异以"裁剪设计稿"解决（见 ui-design.md「与设计稿的差异」） |
| 契约优先（前后端分离） | ✅ | `contracts/openapi.yaml` 先行；前端类型由契约生成；契约变更须先改契约与失败用例 |
| 测试先行 | ✅ | tasks 阶段每个行为任务先列失败测试；E2E 仅覆盖关键旅程 |
| 单元测试覆盖率 ≥ 90%（行覆盖率，各子项目） | ✅（待落地） | 后端 JaCoCo、前端 Vitest v8 均配置 90% 阈值并接入 CI；排除路径（生成代码、启动类、纯配置）在各自 `AGENTS.md` 明示 |
| 安全与秘密 | ✅ | 无密钥入库；开发种子账号仅为明示的测试值；日志脱敏；示例配置与真实环境变量分离 |
| 子项目文档约定 | ⏳ | 实现首个任务须创建 `backend/AGENTS.md`、`frontend/AGENTS.md` 及各自 `CLAUDE.md`（内容 `@AGENTS.md`），含栈、命令、覆盖率与 CI、环境变量 |
| 完成后归档 | ⏳ | 功能完成后把 spec/设计归档至 `docs/achieve-specs/`、架构归档至 `docs/system-design/` |

**Phase 1 设计后复核**：以上结论不变，无需 Complexity Tracking 项。

## Project Structure

### Documentation (this feature)

```text
specs/001-tenant-admin-center/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── openapi.yaml     # REST 契约（后端与前端的唯一接口约定）
│   └── ui-design.md     # 界面契约：页面、设计 token、与参考设计稿的映射与差异
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
backend/
├── AGENTS.md / CLAUDE.md
├── pom.xml                # 单模块（groupId tech.toolkit.tenantcore，Spring Boot 4.1.2 parent，Java 21）
├── src/main/java/tech/toolkit/tenantcore/
│   ├── identity/          # User（含用户名密码凭证）、用户管理用例；日后的 Account 类型也落在这里
│   ├── auth/              # 登录判定、改密、锁定策略、Authenticator 策略、会话（创建/解析/续期/撤销、定时清理）
│   ├── audit/             # 审计日志写入与查询
│   ├── platform/          # 租户上下文、内部 JWT 校验、密码编码、时钟等横切能力
│   ├── api/               # 仅 Web 层：REST 控制器、内部 JWT 认证过滤器、错误处理（problem+json）
│   └── bootstrap/         # 非 Web 运行模式：在默认租户（tenant_id=0）预置首位管理员
├── src/main/resources/db/migration/   # Flyway：V1__init.sql（表 + RLS + 约束）
└── src/test/java/...      # 单元（*Test）/ Testcontainers 集成（*IT）/ OpenAPI 契约测试 / 跨租户隔离测试集 / ArchUnit 分层测试

frontend/
├── AGENTS.md / CLAUDE.md
├── next.config.ts         # output: standalone；不使用 rewrites，/api/v1/* 由 BFF 路由处理器代理（需附加内部 JWT）
├── src/
│   ├── app/               # App Router
│   │   ├── (public)/      # 登录 `/login`
│   │   ├── (console)/     # 登录后外壳（侧边栏 + 顶栏）：users, users/new, users/[id], users/[id]/edit, sessions, change-password, forbidden；`/` 重定向到 `/users`
│   │   ├── api/v1/        # BFF 路由处理器：auth/login、auth/logout（设置/清除会话 Cookie）与 [...path]（通用代理：解析会话 → 签发 JWT → 转发）
│   │   └── globals.css    # Tailwind 4 @import + @theme 设计 token
│   ├── middleware.ts      # 无会话 Cookie 时跳转 `/login`（仅体验层，真正鉴权在后端）
│   ├── server/            # 仅服务端：session（Cookie、会话解析器）、backend（JWT 签发 + 后端客户端）、rate-limit、client-ip
│   ├── features/          # auth, users, credentials（表单、登录账号区、一次性临时密码弹窗等）
│   ├── components/ui/     # shadcn 风格原子组件：Button, Input, Badge, Table, Pagination, Dialog, Switch, StickyActionBar…
│   └── lib/               # api（由 openapi.yaml 生成的类型 + 浏览器端 fetch 封装：错误码映射、会话过期处理）、validation（zod）、utils（cn）
└── tests/                 # 组件测试（Vitest）、E2E（Playwright）
```

**Structure Decision**: 采用 web-application 结构，`backend/` 与 `frontend/` 为两个独立可部署单元（FR-024）。后端为 Maven **单模块**，按**业务能力**分包（`tenant`、`identity`、`auth`、`audit`），便于后续新增 Account 类型只落在 `identity/` 与 `auth/`；分层靠包边界而非模块边界，并以 ArchUnit 约束：业务包不得依赖 `api` 包，`bootstrap` 与 `api` 互不依赖。仓库根不放构建脚本命令，命令只写在各子项目 `AGENTS.md`。

## Complexity Tracking

无需填写：Constitution Check 无违规项。
