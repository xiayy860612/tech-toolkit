# AGENTS — apps/admin-center（Admin Center：接入层 Web Server）

遵循仓库根 [`AGENTS.md`](../../AGENTS.md)；本文件补充 Admin Center 的技术栈、命令与门禁。规格见 [`specs/002-admin-center-uam/`](../../specs/002-admin-center-uam/)，UAM 契约见 [`contracts/uam/openapi.yaml`](../../contracts/uam/openapi.yaml)，Web 路由契约见 [`specs/002-admin-center-uam/contracts/web-routes.md`](../../specs/002-admin-center-uam/contracts/web-routes.md)。

## 技术栈与目录

- TypeScript 5、Node.js LTS（22）、Next.js 15（App Router、SSR、Server Actions、`src/` 目录）、React 19、Tailwind CSS 4、shadcn/ui（Radix UI，`new-york` 风格）、zod。
- 测试：Jest + React Testing Library、msw（UAM 桩，按 OpenAPI 校验）、Playwright（E2E 与视口矩阵 320 / 375 / 768 / 1024 / 1440 / 2560）、`@axe-core/playwright`。
- 目录：
  - `src/app/`：路由（`(shell)` 带外壳、`(auth)` 极简外壳、`logout`、`session/expire`）
  - `src/components/`：`ui/`（shadcn 供应商代码，覆盖率排除）、`shell/`、`auth/`、`users/`、`sessions/`
  - `src/lib/`：服务端模块一律 `import "server-only"`，含 `config`、`uam/`、`session/`、`security/`、`validation/`
  - `src/instrumentation.ts`：服务启动时校验环境变量（缺 `UAM_SERVICE_KEY` 即启动失败）
  - `tests/{unit,contract,e2e,support}/`
- Admin Center **无状态**、不存密码、不连数据库；`UAM_SERVICE_KEY` 只在服务端使用，绝不进入浏览器包。它是 UAM 的**客户端**（Client）：每个请求带 `Authorization: Bearer <服务密钥>`，写操作与会话页另带 `X-Operator-*`。

## 命令（在 `apps/admin-center/` 下执行）

```bash
npm install            # 安装依赖
npm run dev            # 开发服务器
npm run lint           # lint
npm run typecheck      # 类型检查
npm run test           # 单元 / 契约测试
npm run test:cov       # 带覆盖率（行覆盖率 < 90% 则失败）
npm run build          # 生产构建
npm run e2e            # Playwright E2E（需要先启动栈，见 infra/local/scripts/bootstrap.sh）
npm run gen:api        # 由 contracts/uam/openapi.yaml 重新生成 src/lib/uam/types.generated.ts
```

## 测试与覆盖率

- 测试先行（red → green → refactor）：新行为或缺陷修复先写会失败的测试。
- **覆盖率口径：行覆盖率 ≥ 90%**，工具 Jest 内置覆盖率（`coverageThreshold`），CI 同样执行。
- **排除路径**（只允许放不含业务逻辑的代码，终审在 PR 评审）：`src/components/ui/**`（shadcn/ui 供应商代码）、`**/*.generated.ts`（由 OpenAPI 生成）、`src/**/*.d.ts`。

## 环境变量与示例配置

见 `.env.example`（仅占位值）。真实值只通过部署环境注入，禁止入库。缺少 `UAM_SERVICE_KEY` 或 `UAM_BASE_URL` 时服务**启动**失败（构建不受影响）。

| 变量 | 说明 | 默认 |
|---|---|---|
| `UAM_BASE_URL` | UAM 地址 | 必填 |
| `UAM_SERVICE_KEY` | 调用 UAM 的服务密钥（`generate-service-key` 生成，仅输出一次） | 必填 |
| `UAM_TIMEOUT_MS` | 调用 UAM 的超时 | `1500` |
| `SESSION_COOKIE_NAME` | 会话 cookie 名（本地 http 请用 `asid`） | `__Host-asid` |
| `TRUSTED_PROXY_HOPS` | 取终端来源地址时信任的反向代理跳数 | `1` |
