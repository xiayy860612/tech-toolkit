# Tenant Core MVP（已落地现状）

本文是 `specs/001-tenant-core-mvp/` 完成后的归档，描述**当前真实状态**；过程性材料（spec、plan、tasks、research）仍留在 `specs/001-tenant-core-mvp/`。架构层面的调整记录在 [`docs/system-design/06-decisions.md`](../system-design/06-decisions.md) 的 D32–D35。

## 范围

单个 cell 内的 Tenant Core 最小闭环：

1. 运维用 Bootstrap CLI 创建 Tenant，启用 username/password Login Method，创建 User，为其创建 Account，订阅 `platform` Module。
2. 用户在 platform web 用 Tenant Slug + username + 密码登录；首次登录必须先改初始密码。
3. 登录后左侧导航三项：租户管理、模块管理、用户管理，对应三个只读分页列表。

不在范围内：网关、多 cell、Platform Service、RBAC、其他 Login Method、找回密码、写操作 UI。

## 术语（Ubiquitous Language）

术语表以 [`specs/001-tenant-core-mvp/spec.md`](../../specs/001-tenant-core-mvp/spec.md) 为准，代码命名映射见同目录 `plan.md`。核心关系：

- **User** 是租户内的一个人（对外 `tenantUserId`，`tu_…`）；**Account** 是登录凭据（username + 密码），关联且仅关联一个 User，一个 User 可有多个 Account（MVP 每人一个密码 Account）。
- **Module** 只指可订阅的产品模块（MVP 仅 `platform`）；代码层面的划分叫 package 或子项目。

## 组成

| 部分 | 位置 | 说明 |
|---|---|---|
| 共享库 | `backend/core-lib` | `tenant`、`uam`、`audit` 三个 package + Flyway 迁移 + `CoreComponents` 装配 |
| API | `backend/tenant-core-api` | Spring Boot REST，契约见 `specs/001-tenant-core-mvp/contracts/tenant-core-api.yaml` |
| CLI | `backend/tenant-core-cli` | picocli，直连数据库，可执行 jar + `bin/tenant-core-cli` |
| Web | `apps/platform-web` | Next.js SSR，服务端即 BFF，Session 存 Redis |
| 本地依赖 | `deploy/docker-compose.yml` | PostgreSQL 16 + Redis 7 |

## 数据库

单一 schema（`public`），表名前缀即模块边界：`core_tenant`、`core_module_subscription`、`uam_tenant_login_method`、`uam_user`、`uam_account`、`uam_credential_password`、`uam_opaque_token`、`audit_event`。package 之间不建外键、不联表；以后拆服务时按前缀迁表。`audit_event` 对应用角色 `tenant_core_app` 撤销了 UPDATE/DELETE/TRUNCATE（只追加）。迁移由 owner 角色执行，运行时用 `tenant_core_app`。

## 认证与令牌

- 登录：`POST /v1/auth/login`（`tenantSlug`、`username`、`password`）。所有失败统一为 401 `INVALID_CREDENTIALS`（同文案、同耗时，不存在的账户也做一次 Argon2 校验）；连续 5 次失败锁定 15 分钟且不暴露锁定状态；只有在密码正确后才会返回 403 `MODULE_NOT_SUBSCRIBED`。
- **Opaque Token**：session id，`tk_` 前缀，库中只存 SHA-256；空闲 30 分钟、绝对 8 小时；经请求头 `X-Session-Id` 携带；仅 BFF 持有，浏览器只有 HttpOnly cookie。
- **Internal JWT**：`POST /v1/auth/exchange` 用 Opaque Token 换发，ES256，默认 120 秒，声明见 `data-model.md`；经 `Authorization: Bearer` 携带；公钥在 `/.well-known/jwks.json`。要求改密的 Session 不可换发。
- 请求头决定校验方式：两个头并存、都缺失、放错位置一律 401；登录、退出、改密、换发只接受 `X-Session-Id`；要求改密的 Session 只能改密、退出或 `GET /v1/auth/me`。
- 签名私钥只来自环境变量 `UAM_SIGNING_KEY_PEM`；只有 dev/test profile 可生成临时密钥。

## Bootstrap CLI

```text
tenant-core-cli tenant create        --slug <slug> --name <name>
tenant-core-cli login-method enable  --tenant <slug> --type password
tenant-core-cli user create          --tenant <slug> --display-name <name>
tenant-core-cli account create       --tenant <slug> --user <tu_…> --username <name> [--password-stdin]
tenant-core-cli subscription add     --tenant <slug> --module platform
tenant-core-cli tenant show          --tenant <slug>          # 只读核对
```

全局 `--output json`；所有写命令幂等并写审计（actor `cli:<os-user>@<host>`）。退出码：0 成功（含幂等）、1 校验错误、2 前置条件缺失、3 冲突。密码只能经隐藏提示或 `--password-stdin` 输入，没有 `--password`，且不会回显未知参数。

## 平台控制台界面

- 技术：Tailwind CSS 4 + shadcn/ui；界面文案全中文，状态显示为“正常 / 已暂停 / 已停用 / 已订阅”，时间按浏览器本地时区，没有 Account 的 User 用户名显示“—”。
- 骨架：全局顶栏（左“Platform”，右用户菜单）+ 左侧栏（仅租户管理 / 模块管理 / 用户管理）+ 限宽内容区；各列表页有统一页头（标题 + 操作位，当前只读故操作位为空）。
- 用户菜单：按钮显示 Display Name，展开后显示 Display Name、username、Tenant Slug 与“退出登录”（POST /logout）；这是退出登录的唯一入口。
- 自适应：≥ 768px 侧边栏 + 表格；< 768px 顶栏最左出现菜单按钮，点开 Sheet 显示三个导航项；< 640px 列表为卡片；任一宽度无横向滚动；登录与改密页居中，控件高度 ≥ 44px。
- 规格：FR-024 至 FR-028、SC-009、SC-010；E2E 标签 `@1.0.0`（主旅程另带 `@happy-path`），`pnpm e2e --tag 1.0.0`。

## 前端性能（FR-029、SC-011）

- **预算**：LCP ≤ 2.5 s、INP ≤ 200 ms、CLS ≤ 0.1、FCP ≤ 1.8 s、TTFB ≤ 0.8 s、TBT ≤ 200 ms、Speed Index ≤ 3.4 s、Lighthouse 评分 ≥ 90（移动端）、每路由首次加载 JS ≤ 170 KB（gzip）、列表页（500 条）首屏 ≤ 2 s。唯一来源 `apps/platform-web/scripts/perf-budget.mjs`。
- **实测（2026-11-07，本机 Chrome、生产构建、500 条 User、每项 5 次取中位数）**：全部达标。移动端（模拟 Slow 4G + 4× CPU 降速）LCP 0.60–0.77 s、TBT ≤ 4 ms、CLS ≤ 0.001、评分 100；桌面 LCP 0.17–0.21 s、CLS ≤ 0.017；首次加载 JS：登录与改密页约 116 KB，三个列表页约 152 KB。交互（用户菜单、导航点击、Sheet 开关）均低于 Event Timing 的 16 ms 下限，远低于 200 ms。
- **优化记录**：控制台页首次加载 JS 起初约 192 KB（超 170 KB 预算），`next.config.ts` 加 `optimizePackageImports`（`radix-ui`、`lucide-react`）后降到约 150 KB；该配置是守住预算的关键，勿删。
- **局限**：实验室数据，后端与浏览器同机（TTFB 因此偏小），Core Web Vitals 的正式口径是真实用户数据，上线后应补真实用户监测复核。运行方式见 `apps/platform-web/AGENTS.md`（`pnpm perf`），不进 CI 阻塞门禁。

## 验证状态

| 项 | 结果 |
|---|---|
| 后端 `mvn verify` | 通过：测试、打包 CLI 冒烟、行覆盖率每个子项目 ≥ 90%、Spotless |
| 前端 `pnpm lint`、`typecheck`、`test:coverage`、`build` | 通过：155 个测试，行覆盖率 100% |
| E2E（Playwright，真实 Postgres + Redis + API + Web + 打包 CLI） | 13 个通过：未登录重定向、统一失败文案、强制改密、退出、令牌被后端吊销后回到登录页、导航三项、三个列表与分页、无写入口 |
| 日志与审计中的口令、令牌、哈希 | 检索命中 0 |

## 运行与测试

见 [`backend/AGENTS.md`](../../backend/AGENTS.md) 与 [`apps/platform-web/AGENTS.md`](../../apps/platform-web/AGENTS.md)。E2E 需要 Postgres、Redis、API、Web 都已启动，并设置 `TENANT_CORE_CLI` 等环境变量（见 `apps/platform-web/.env.example`）；Web 在生产/E2E 下建议用 `node .next/standalone/server.js`（先把 `.next/static` 复制到 `.next/standalone/.next/`）。

## 已知限制与后续

- 没有网关：BFF 直接用 Opaque Token 调用；引入网关后由 ext_authz 调换发接口，UAM 与下游不改。
- JWKS 在 cell 内网暴露只靠部署层 NetworkPolicy，应用本身没有限制。
- 登录只做按账户的失败锁定，没有 IP 级限频（D22 的限流部分待做）。
- 租户注册表与 Platform Service 的对账方式仍是 06 的待定项。
- 暂无 tenant_user 档案与 RBAC，三个列表对所有登录用户可见。
