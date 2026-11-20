# AGENTS（frontend）

Tenant Admin Center 的前端，同时是平台分层中的 **Tenant Admin BFF**（入口层）。规格与设计见 `specs/001-tenant-admin-center/`（`contracts/openapi.yaml`、`contracts/bff-openapi.yaml`、`contracts/ui-design.md`）；根 `AGENTS.md` 的原则（测试先行、覆盖率门禁、契约优先、最小改动）在此同样适用。

## 技术栈与目录

- Next.js 15（App Router）· React 19 · TypeScript · Tailwind CSS 4（`@theme` 承载设计 token）· radix-ui + cva（shadcn 风格组件）· zod · jose · ioredis · pnpm · Node ≥ 22
- 测试：Vitest + Testing Library + jsdom（覆盖率 v8）；E2E：Playwright
- `src/app/`：`(public)/login`、`(console)/…`（用户、会话管理、改密、无权限）、`api/v1/auth/{login,logout}`（BFF 自有路由）、`api/v1/[...path]`（通用代理）
- `src/server/`：**仅服务端**——`session/`（Cookie、resolve）、`backend/`（内部 JWT 签发与后端客户端）、`rate-limit`、`client-ip`、`origin`、`config`
- `src/features/`：auth、users、accounts、sessions；`src/components/ui/`：原子组件；`src/lib/`：api 客户端与生成类型、validation、utils
- `tests/unit`、`tests/component`（Vitest）、`tests/e2e`（Playwright）

## BFF 职责（务必遵守）

- 浏览器只持后端签发的不透明会话令牌（HttpOnly Cookie）；**BFF 不保存会话数据**，只在内存短 TTL 缓存 resolve 结果（`SESSION_RESOLVE_CACHE_TTL_SECONDS`，默认 5，0 关闭）；Redis 仅用于限流
- 每次请求：Cookie → 后端 `POST /auth/sessions/resolve`（`typ=service` JWT，`tenant_id=DEFAULT_TENANT_ID`）→ 签发 `typ=user` JWT（`sub`、`tenant_id`、`account_id`、`sid`、`cip`，EdDSA，60 秒；**不含 `role`**）→ 转发后端
- 浏览器自带的 `X-Tenant-*`、`tenantId`、`Authorization`、`Cookie` 一律不转发、不进 JWT
- 只有 `/api/v1/auth/login`、`/api/v1/auth/logout` 是自有路由，其余均为纯代理；非 GET/HEAD 校验 `Origin` 与 `Sec-Fetch-Site`；**不使用 Next.js rewrites**
- 登录与租户无关（无租户入口页）；界面不得出现「忘记密码」「企业 SSO」「扫码登录」「7 天内免登录」

## 命令

| 目的                                       | 命令                                                                |
| ------------------------------------------ | ------------------------------------------------------------------- |
| 安装依赖                                   | `pnpm install`                                                      |
| 生成开发用 JWT 密钥（到被忽略的 `.keys/`） | `pnpm keys`                                                         |
| 由契约生成 API 类型                        | `pnpm generate:api`                                                 |
| 开发                                       | `pnpm dev`（需要 `.env.local`，见下）                               |
| lint（ESLint + Prettier）                  | `pnpm lint`                                                         |
| 类型检查                                   | `pnpm typecheck`                                                    |
| 单元/组件测试                              | `pnpm test`                                                         |
| 覆盖率门禁（行覆盖率 ≥ 90%）               | `pnpm test:coverage`                                                |
| 构建                                       | `pnpm build`                                                        |
| E2E                                        | `pnpm test:e2e`（需要后端、PostgreSQL、Redis 与已启动的前端，见下） |

## 质量门禁

- 合并前：`pnpm lint`、`pnpm typecheck`、`pnpm test:coverage`、`pnpm build` 全部通过（CI：`.github/workflows/frontend.yml`）
- 行覆盖率 **≥ 90%**（Vitest v8）；排除路径：生成的 `src/lib/api/schema.d.ts`、`src/lib/api/bff-schema.d.ts` 与 `*.d.ts`
- 行为变更先写会失败的测试（红 → 绿 → 重构）；组件/BFF 单测用桩后端与 `ioredis-mock`，不依赖真实后端
- 契约变更须先改 `contracts/*.yaml` 与失败用例，再 `pnpm generate:api`

## 环境变量

见 `.env.example`（复制为 `.env.local`，已被 `.gitignore` 忽略）。**密钥与 `.keys/` 不入库。**

| 变量                                                                       | 说明                                                                                                                                                                                                               |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `BACKEND_URL`                                                              | 后端基址，BFF 在其后直接拼契约路径（如 `/auth/login`、`/users`），**不含** `/api/v1`；当前后端为 `http://localhost:8080`                                                                                           |
| `REDIS_URL`                                                                | 仅用于限流计数                                                                                                                                                                                                     |
| `DEFAULT_TENANT_ID`                                                        | 本期恒为 `0`；只进 `typ=service` JWT，登录请求体不含租户                                                                                                                                                           |
| `SESSION_COOKIE_NAME`、`COOKIE_SECURE`                                     | 会话 Cookie 名；非 https 的本地开发设 `COOKIE_SECURE=false`，生产必须为 `true`                                                                                                                                     |
| `SESSION_RESOLVE_CACHE_TTL_SECONDS`                                        | resolve 内存缓存 TTL，默认 5，`0` 关闭                                                                                                                                                                             |
| `INTERNAL_JWT_PRIVATE_KEY_PATH` / `_ISSUER` / `_AUDIENCE` / `_TTL_SECONDS` | 内部 JWT 私钥（JWK，`pnpm keys` 生成）与声明，默认 `admin-center-bff` / `tenant-core-api` / 60 秒                                                                                                                  |
| `BACKEND_TIMEOUT_MS`                                                       | 调用后端的超时，默认 10000                                                                                                                                                                                         |
| `TRUSTED_PROXY_HOPS`                                                       | 信任的反向代理层数。Next 的路由处理器拿不到对端 socket 地址，`0` 时客户端 IP 记为 `unknown`（限流共用一个桶）；**生产在反向代理后设为代理层数**，本地直连开发设 `1`（Next 会用 socket 地址填充 `X-Forwarded-For`） |
| `LOGIN_RATE_LIMIT_MAX` / `_WINDOW_SECONDS`                                 | 登录限流，默认每 IP 每 60 秒 20 次；Redis 不可用时放行并只记录错误类别                                                                                                                                             |

## 本地联调（E2E）——已按此验证

1. `cd backend && docker compose up -d`（PostgreSQL + Redis）。后端应用角色 `tenantcore_app` 由迁移创建为 `NOLOGIN`，本地需先让它可登录：`docker exec backend-postgres-1 psql -U tenantcore -d tenantcore -c "CREATE ROLE tenantcore_app LOGIN PASSWORD '<dev 密码>'"`（迁移里是 `IF NOT EXISTS`，会保留该角色）
2. `pnpm keys` 生成密钥；启动后端：`DB_APP_PASSWORD=<上面的密码> DB_MIGRATION_PASSWORD=tenantcore-dev-only INTERNAL_JWT_PUBLIC_KEY=<绝对路径>/frontend/.keys/internal-jwt-public.jwk.json mvn spring-boot:run -Dspring-boot.run.profiles=dev`（公钥是 **JWK 文件**）。`dev` profile 会种子管理员 `admin`（初始密码见 `application-dev.yml`，首次登录须改密）
3. 在 `.env.local` 写入 `COOKIE_SECURE=false`、`TRUSTED_PROXY_HOPS=1`、`LOGIN_RATE_LIMIT_MAX=1000`，再 `pnpm build && pnpm start`
4. `pnpm test:e2e`。Playwright 版本对应的 Chromium 未安装时，用本机 Chrome：`E2E_CHANNEL=chrome pnpm test:e2e`。用例对重复运行幂等（种子管理员改密到 `E2E_ADMIN_PASSWORD`，其余数据用随机用户名）
5. 注意：登录失败会累计锁定（5 次 / 15 分钟）；若 E2E 因种子管理员被锁，在**本地开发库**执行 `update account set failed_attempts=0, locked_until=null where identifier='admin'`

## 实现要点与已知坑

- **认证状态变化后必须整页跳转**（`hardNavigate`，见 `src/lib/navigation.ts`）：同一 `(console)` 布局下的软导航不会重新执行布局里的服务端守卫（须改密 / 角色 / 会话），`router.replace` 之后紧跟 `router.refresh()` 还会竞态出空白页。登录与改密成功都用整页跳转
- 外壳守卫的角色取自 `/auth/me`（数据库当前值）；管理员被降级后，已打开页面内的软导航不会重跑守卫，但后端每个接口仍按库内角色 403，页面按错误码提示
- `/auth/login`、`/auth/sessions/resolve` 是仅供 BFF 以 `typ=service` 调用的端点，通用代理对它们一律 404
- E2E 里 `getByRole("alert")` 会同时命中 Next 的路由播报区，表单提示请用 `formAlert(page)`（`tests/e2e/helpers.ts`）
- 租户固定为 0，无法经 UI 验证「同名用户名在另一租户可创建」，该约束由后端 `SameUsernameAcrossTenantsIT` 覆盖
