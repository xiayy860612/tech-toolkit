# Admin Center — 架构与实现（现状归档）

> **归档状态**：特性 `002-admin-center-uam` 已交付并归档。本文是**Admin Center（`apps/admin-center`）的架构与实现**的当前真实状态；需求规格见 [docs/achieve-specs/admin-center/spec.md](../../achieve-specs/admin-center/spec.md)；需求对照、验证记录与已知限制见 [traceability](../system/traceability.md)，UAM 领域与接口见 [domain-and-api](../uam/domain-and-api.md)。
> 设计按 app 拆分：[system](../system/README.md)（跨服务）· [uam](../uam/README.md) · [admin-center](./README.md)；各目录的 `plan.md`、`research.md`、`data-model.md` 是过程记录，与本文不一致时**以本文和代码为准**。
> 图的 PlantUML 源文件与 SVG 在各目录的 `diagrams/` 下；改图后重新渲染：`java -jar plantuml.jar -tsvg -charset UTF-8 docs/system-design/*/diagrams/*.puml`（GitHub 不渲染 PlantUML，SVG 须与源文件一同提交）。

跨服务的视图（C4 L1/L2、运行时与部署、来源地址信任模型）见 [系统级架构](../system/README.md)。

## 1. 总览

| 服务 | 路径 | 技术 | 状态 |
|---|---|---|---|
| UAM | `apps/uam` | Java 21、Spring Boot 4.0.x（Web MVC、Validation、JDBC、Data Redis / Lettuce、Actuator、Flyway 社区版）、`spring-security-crypto` + BouncyCastle（Argon2id）、Micrometer | 无实例本地状态；状态在 PostgreSQL 与 Redis |
| Admin Center | `apps/admin-center` | TypeScript 5、Node 22、Next.js 15（App Router、SSR、Server Actions）、React 19、Tailwind CSS 4、shadcn/ui（Radix）、zod | 无状态、无存储；`output: "standalone"` |

## 2. 组件

### 2.1 L3 组件

![L3 Admin Center](./diagrams/c4-l3-admin-center-component.svg)

源文件：[c4-l3-admin-center-component.puml](./diagrams/c4-l3-admin-center-component.puml)。路径相对 `apps/admin-center/src`：

| 组件 | 位置 | 职责 |
|---|---|---|
| `middleware` | `middleware.ts` | 设置 CSP（含 nonce）与安全响应头；写 `x-next-path`（总被覆盖）供布局重定向；**不发网络请求** |
| Shell 布局 | `app/(shell)/layout.tsx`、`components/shell/*` | 读会话三态；已登录渲染侧边栏 + 顶栏，匿名与 `unverified` 只有顶栏；桌面 ≥ 1024 展开、平板 768–1023 图标条、手机抽屉 |
| 页面（RSC） | `app/(shell)/{users,sessions,account/password}`、`app/(auth)/login` | 经 `UamClient` 取数据 |
| Server Actions | `*/actions.ts` | 全部写入口：先同源校验与表单校验，再读会话、调 UAM |
| 路由处理器 | `app/logout/route.ts`（仅 POST）、`app/session/expire/route.ts` | 退出；重新 `verify` 仍无效才清 cookie（页面渲染期间不能写 cookie，故单设此路由） |
| `getSession` | `lib/session/get-session.ts` | 读 cookie → `verify`；三态 `authenticated` / `anonymous` / `unverified`；请求内 React `cache` 去重 |
| `cookie` | `lib/session/cookie.ts` | 写 / 读 / 清会话 cookie |
| `UamClient` | `lib/uam/client.ts`、`errors.ts` | **唯一**调用 UAM 的出口：`X-Operator-*`、超时（默认 1.5 秒）、透传请求自带的 `traceparent`；错误分类 `problem` / `unavailable`；`server-only` |
| `OriginCheck` | `lib/security/origin-check.ts` | 同源校验（CSRF） |
| `ClientIp` | `lib/session/client-ip.ts` | 按 `TRUSTED_PROXY_HOPS` 取 `X-Forwarded-For` 右数第 N 项并校验为合法 IP |
| 配置与启动校验 | `lib/config.ts`、`instrumentation.ts` | zod 解析环境变量（运行期解析，不在构建期）；缺 `UAM_BASE_URL` 即启动失败 |
| 表单校验 | `lib/validation/*`、`lib/session/next-path.ts` | 字段级校验；`next` 站内路径白名单 |

`src/lib` 下的服务端模块一律 `import "server-only"`（纯函数 / 生成类型的例外见 `security-check.sh` 的允许名单）。

界面设计原型：[prototype/](./prototype/README.md)（静态、可点击；`us11-*` E2E 校验实现与原型一致）。页面、路由、cookie 与错误文案契约见 [web-routes.md](./web-routes.md)。

## 3. 安全设计

| 主题 | 设计与实现 |
|---|---|
| **cookie** | `__Host-asid`（开发可用 `SESSION_COOKIE_NAME=asid`）：`HttpOnly; Secure; SameSite=Strict; Path=/`，无 `Domain`；清除时用同样属性写立即过期的空值。本地 http 下 Safari 通常不接受 Secure cookie（用 HTTPS 入口或换浏览器）。 |
| **同源校验（CSRF）** | 所有写入口第一步 `isSameOriginRequest`：非 GET / HEAD / OPTIONS 时，有 `Sec-Fetch-Site` 须为 `same-origin`；有 `Origin` 时其主机须等于 `X-Forwarded-Host` 第一个值（否则 `Host`）；二者都没有则拒绝；失败 403。`SameSite=Strict` 是第二层。`POST /logout` 只接受 POST（GET 回 405）。UAM 不处理 cookie，无 CSRF 面。 |
| **CSP 与响应头** | `middleware` 为每个请求生成 nonce：`default-src 'self'`；`script-src 'self' 'nonce-…' 'strict-dynamic'`（开发模式加 `'unsafe-eval'`）；`style-src 'self' 'unsafe-inline'`（Radix / shadcn 依赖内联样式属性）；`img-src 'self' data:`；`font-src 'self'`；`connect-src 'self'`；`object-src 'none'`；`base-uri 'self'`；`form-action 'self'`；`frame-ancestors 'none'`。另设 `X-Content-Type-Options: nosniff`、`Referrer-Policy: same-origin`；`poweredByHeader: false`。 |
| **启动与构建** | Admin Center 缺 `UAM_BASE_URL` 启动失败（`instrumentation.ts`，只列变量名不回显值）；`src/lib` 服务端模块一律 `server-only`；UAM 本身不校验调用方，访问控制靠网络隔离（UAM 只对 Admin Center 实例开放），服务身份认证留待后续特性；`package.json` 不得出现数据库驱动 / ORM。 |
| **来源地址** | 见 [系统级 §4.3](../system/README.md)。 |

## 4. 可观测性

| 项 | 实现 |
|---|---|
| 指标 / 探针 | Admin Center **没有**专门的指标端点与探针端点；编排用 `GET /login` 探活（匿名不调用 UAM，只反映本进程是否在服务）。 |

## 5. CI 与质量门禁

（`apps/admin-center`，`.github/workflows/admin-center-ci.yml`；命令以 [`apps/admin-center/AGENTS.md`](../../../apps/admin-center/AGENTS.md) 为准）

| 项 | Admin Center（`apps/admin-center`，`.github/workflows/admin-center-ci.yml`） |
|---|---|
| lint | `npm run lint` |
| typecheck | `npm run typecheck` |
| 测试 | Jest + React Testing Library；msw 的 UAM 桩按 OpenAPI 校验；Playwright E2E（六个视口 project：320 / 375 / 768 / 1024 / 1440 / 2560，Chromium）与 `@axe-core/playwright`（E2E 需要本地栈，不在 CI 工作流里） |
| 覆盖率门禁 | Jest **行覆盖率 ≥ 90%**（`coverageThreshold: { global: { lines: 90 } }`） |
| 排除路径 | `src/components/ui/**`（shadcn 供应商代码）、`src/**/*.generated.ts`（OpenAPI 生成）、`src/**/*.d.ts`；`instrumentation.ts`、`middleware.ts` 都计入 |
| build | `npm run build`（不需要运行期环境变量） |

## 6. 容器镜像

`apps/admin-center/Dockerfile`：Next.js standalone，非 root，监听 `PORT=3000`、`HOSTNAME=0.0.0.0`；构建期不需要运行期环境变量。

## 7. 相关文档

- 过程记录：[plan.md](./plan.md)、[research.md](./research.md)；界面原型 [prototype/](./prototype/README.md)。
- 需求对照、验证记录、已知限制：[traceability.md](../system/traceability.md)。
