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

| 目的 | 命令 |
|---|---|
| 安装依赖 | `pnpm install` |
| 生成开发用 JWT 密钥（到被忽略的 `.keys/`） | `pnpm keys` |
| 由契约生成 API 类型 | `pnpm generate:api` |
| 开发 | `pnpm dev`（需要 `.env.local`，见下） |
| lint（ESLint + Prettier） | `pnpm lint` |
| 类型检查 | `pnpm typecheck` |
| 单元/组件测试 | `pnpm test` |
| 覆盖率门禁（行覆盖率 ≥ 90%） | `pnpm test:coverage` |
| 构建 | `pnpm build` |
| E2E | `pnpm test:e2e`（需要后端、PostgreSQL、Redis 与已启动的前端，见下） |

## 质量门禁

- 合并前：`pnpm lint`、`pnpm typecheck`、`pnpm test:coverage`、`pnpm build` 全部通过（CI：`.github/workflows/frontend.yml`）
- 行覆盖率 **≥ 90%**（Vitest v8）；排除路径：生成的 `src/lib/api/schema.d.ts`、`src/lib/api/bff-schema.d.ts` 与 `*.d.ts`
- 行为变更先写会失败的测试（红 → 绿 → 重构）；组件/BFF 单测用桩后端与 `ioredis-mock`，不依赖真实后端
- 契约变更须先改 `contracts/*.yaml` 与失败用例，再 `pnpm generate:api`

## 环境变量

见 `.env.example`（复制为 `.env.local`；开发环境非 https 时设 `COOKIE_SECURE=false`）。关键项：`BACKEND_URL`、`REDIS_URL`、`DEFAULT_TENANT_ID`（恒 0）、会话 Cookie 名、`SESSION_RESOLVE_CACHE_TTL_SECONDS`、内部 JWT 私钥路径/issuer/audience/有效期、`TRUSTED_PROXY_HOPS`。**密钥与 `.keys/` 不入库。**

## 本地联调（E2E）

1. `cd backend && docker compose up -d`（PostgreSQL + Redis）
2. `pnpm keys`；启动后端（`dev` profile，`INTERNAL_JWT_PUBLIC_KEY` 指向 `frontend/.keys/internal-jwt-public.jwk.json`），种子管理员 `admin`（密码见 `backend/src/main/resources/application-dev.yml`）
3. 启动前端（`COOKIE_SECURE=false pnpm dev` 或 `pnpm build && pnpm start`），再运行 `pnpm test:e2e`
