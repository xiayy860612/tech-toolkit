# AGENTS（apps/platform-web）

Platform 的 Web 控制台（SSR）。Next.js 服务端即 BFF：浏览器只持有 HttpOnly session cookie，Opaque Token 保存在 Redis Session 中，由服务端经 `X-Session-Id` 请求头调用 `tenant-core-api`。仓库级约定见根目录 `AGENTS.md`，本文件只补充本子项目细节。契约与设计见 `specs/001-tenant-core-mvp/`（`spec.md`、`plan.md`、`contracts/tenant-core-api.yaml`）。

## 技术栈

Node.js 22、Next.js 15（App Router）、React 19、TypeScript 5（strict）、Tailwind CSS 4（`@tailwindcss/postcss`）+ shadcn/ui（Radix、`lucide-react`、`class-variance-authority`、`tw-animate-css`）、ioredis、Vitest + React Testing Library（jsdom）+ ioredis-mock、Playwright、ESLint + Prettier、pnpm。

## 目录

```
src/
  middleware.ts          # Edge：只检查 cookie 是否存在并重定向（不能用 Redis）
  app/                   # 路由：login、change-password、logout、session/expired、healthz、(console)/{tenants,modules,users}
  components/            # 业务组件：AppShell（顶栏 + 侧边栏 / Sheet + 限宽内容区）、TopBar、UserMenu（退出登录唯一入口）、PageHeader（标题 + 操作位）、ResponsiveList（Table / 卡片）、StatusBadge、LocalTime、LoginForm、ChangePasswordForm
    ui/                  # shadcn/ui 生成代码（button、input、label、card、table、badge、alert、sheet、separator、dropdown-menu）
  lib/                   # 纯函数与 DTO 类型（types、pagination、format、safeNextPath、utils 的 cn）
  server/                # 仅服务端：env、session（Redis）、sessionCookie、container、auth（requireSession / runWithSession）、tenantCoreClient
tests/
  unit/  component/      # Vitest
  e2e/                   # Playwright（需完整后端栈，fixtures.ts 有环境说明）
scripts/                 # e2e.mjs（pnpm e2e 入口）、e2e-args.mjs（参数转换，有单元测试）
```

## 命令

在 `apps/platform-web` 下执行（pnpm）：

| 目的                           | 命令                                    |
| ------------------------------ | --------------------------------------- |
| 安装依赖                       | `pnpm install`                          |
| 开发                           | `pnpm dev`                              |
| 构建 / 启动                    | `pnpm build` / `pnpm start`             |
| Lint（ESLint + Prettier 检查） | `pnpm lint`                             |
| 类型检查                       | `pnpm typecheck`                        |
| 单元 / 组件测试                | `pnpm test`                             |
| 覆盖率（含阈值校验）           | `pnpm test:coverage`                    |
| E2E（需后端栈与 CLI）          | `pnpm test:e2e`                         |
| E2E 按标签跑（可多个，取交集） | `pnpm e2e --tag 1.0.0 --tag happy-path` |
| E2E 用 Chrome 有头模式         | `pnpm e2e --chrome --headed`            |
| 新增 shadcn 组件               | `pnpm dlx shadcn@latest add <name>`     |

## 界面约定（Tailwind + shadcn/ui）

- 样式只用 Tailwind 工具类与 `src/app/globals.css` 中的主题变量（`components.json` 配置 shadcn；`postcss.config.mjs` 接入 Tailwind 4）。
- `src/components/ui/**` 由 shadcn CLI 生成，**不要手工编辑**；需要定制时在业务组件里用 `className` / `cn()` 覆盖，或重新用 CLI 生成。CLI 若把 `cn` 导入写成 `from "cn"`，须改回 `@/lib/utils`。
- 响应式断点：`md`（768px）以上为左侧栏 + Table，以下为顶部栏 + Sheet 菜单；`sm`（640px）以下列表为卡片。隐藏的变体用 `hidden` / `md:hidden` 等 display:none 类，保证同一时刻只暴露一个 `主导航`。任何宽度不得出现横向滚动。
- 登录、改密页：居中 Card；输入框与按钮高度 `h-11`（≥ 44px）。
- 文案一律中文；状态、时间、用户名占位、密码策略提示的映射集中在 `src/lib/format.ts`。时间由 `LocalTime` 在浏览器本地时区显示（服务端输出稳定的 UTC 文本，挂载后替换）。表单错误不拼接后端英文原文。

## 测试与覆盖率

- 测试先行（red → green → refactor）；单元与组件测试不依赖后端，Redis 用 `ioredis-mock`，后端调用用 mock `fetch` 或 mock `@/server/container`。
- **覆盖率口径：行覆盖率 ≥ 90%**（`vitest.config.ts` 的 `thresholds.lines`，provider 为 v8，统计范围 `src/**`）。`pnpm test:coverage` 低于阈值会失败，CI 以此命令为门禁。
- **排除路径**（`vitest.config.ts`）：`src/**/*.d.ts`（类型声明）、`src/app/layout.tsx`（根布局：仅 `<html>` 外壳与全局 CSS 引入，无逻辑）、`src/components/ui/**`（shadcn 生成代码，第三方来源、不手工编辑，行为由业务组件的测试间接覆盖）。
- jsdom 不计算 CSS：组件测试断言 DOM 结构与断点类名（如 `hidden sm:block`、`h-11`）；`tests/setup.ts` 提供 `matchMedia`、`ResizeObserver` polyfill（Radix Sheet 需要）并固定 `TZ=UTC`。真实的断点与横向滚动由 `tests/e2e/responsive.spec.ts` 验证。`src/lib/types.ts` 为纯类型，无可执行语句，不计入分母。
- E2E 版本标签：每个 spec 的用例包在 `test.describe('<版本>', { tag: VERSION_x_y_z }, …)` 里（常量见 `fixtures.ts`，现为 `@1.0.0`）；不带 `--tag` 运行全部（`pnpm e2e` 由 `scripts/e2e.mjs` 把 `--tag` 转成 Playwright `--grep`，其余参数原样传给 Playwright；`pnpm test:e2e` 仍可直接用），新版本新增用例时定义新标签常量。主要用户旅程（登录、首次改密、三个列表、响应式导航、退出）另有 `@happy-path` 标签（`HAPPY_PATH`，加在单个用例上）；`--chrome`（即环境变量 `E2E_BROWSER_CHANNEL=chrome`）改用本机 Google Chrome。
- E2E（`tests/e2e`）不计入覆盖率。需要：Postgres、Redis、`tenant-core-api`、`platform-web` 已启动，以及已构建的 `tenant-core-cli`；通过 `tests/e2e/fixtures.ts` 调用 CLI 造数据。旅程会修改 Account 密码，请在全新库上运行或每次更换 `E2E_TENANT_SLUG`。

## 安全约定

- Opaque Token 只存在于服务端（Redis、`src/server`），不得传给客户端组件、不得写日志、不得出现在页面数据中。
- `middleware.ts` 只做粗粒度拦截；真正的 Session 校验（Redis、`mustChangePassword`、后端 401）在 `src/server/auth.ts`。后端返回 401 时销毁 Session，经 `/session/expired`（清 cookie）回到 `/login?next=…`。
- `next` 参数一律经 `safeNextPath` 过滤，防止开放重定向。
- 退出仅接受 `POST /logout`。

## 环境变量

示例见 `.env.example`（不要提交真实值）。

| 变量                                                                                                             | 说明                                                           | 默认                      |
| ---------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- | ------------------------- |
| `TENANT_CORE_API_URL`                                                                                            | `tenant-core-api` 地址（仅服务端）                             | `http://localhost:8080`   |
| `REDIS_URL`                                                                                                      | Session 存储                                                   | `redis://localhost:6379`  |
| `SESSION_COOKIE_SECURE`                                                                                          | cookie 的 Secure 标志；生产默认 `true`，本地 http 可设 `false` | 生产 `true`，否则 `false` |
| `E2E_BASE_URL`、`TENANT_CORE_CLI`、`E2E_TENANT_SLUG`、`E2E_USERNAME`、`E2E_INITIAL_PASSWORD`、`E2E_NEW_PASSWORD` | 仅 E2E 使用，口令必须是一次性测试值                            | 见 `.env.example`         |

## 命名规则（Ubiquitous Language）

业务术语以 `specs/001-tenant-core-mvp/spec.md` 的术语表和 `plan.md` 的“命名约定”为准，组件、类型、路由、测试名使用同一英文术语，不另造同义词：Tenant（`tenantSlug`）、User（`displayName`）、Account（`username`）、Module Subscription（`moduleCode`）、Opaque Token、Internal JWT、Session。代码层面的目录不叫 Module。界面文案保持中文（导航：租户管理 / 模块管理 / 用户管理）。
