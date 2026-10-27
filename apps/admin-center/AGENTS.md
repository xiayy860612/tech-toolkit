# AGENTS — apps/admin-center（Admin Center：接入层 Web Server）

遵循仓库根 [`AGENTS.md`](../../AGENTS.md)；本文件补充 Admin Center 的技术栈、命令与门禁。需求见 [`docs/achieve-specs/admin-center/spec.md`](../../docs/achieve-specs/admin-center/spec.md)，设计见 [`docs/system-design/admin-center/`](../../docs/system-design/admin-center/README.md)，UAM 契约见 [`contracts/uam/openapi.yaml`](../../contracts/uam/openapi.yaml)，Web 路由契约见 [`docs/system-design/admin-center/web-routes.md`](../../docs/system-design/admin-center/web-routes.md)。

## 技术栈与目录

- TypeScript 5、Node.js LTS（22）、Next.js 15（App Router、SSR、Server Actions、`src/` 目录）、React 19、Tailwind CSS 4、shadcn/ui（Radix UI，`new-york` 风格）、zod。
- 测试：Jest + React Testing Library、msw（UAM 桩，按 OpenAPI 校验）、Playwright（E2E 与视口矩阵 320 / 375 / 768 / 1024 / 1440 / 2560）、`@axe-core/playwright`。
- 目录：
  - `src/app/`：路由（`(shell)` 带外壳、`(auth)` 极简外壳、`logout`、`session/expire`）
  - `src/components/`：`ui/`（shadcn 供应商代码，覆盖率排除）、`shell/`、`auth/`、`users/`、`sessions/`
  - `src/lib/`：服务端模块一律 `import "server-only"`（纯函数 / 生成类型的例外见 `security-check.sh` 的允许名单），含 `config`、`uam/`、`session/`、`security/`、`validation/`
  - `src/instrumentation.ts`：服务启动时校验环境变量（缺 `UAM_BASE_URL` 即启动失败）
  - `tests/{unit,contract,e2e,support}/`
- Admin Center **无状态**、不存密码、不连数据库（`package.json` 不得出现数据库驱动 / ORM，由 `security-check.sh` 守住）；与 UAM 通信的配置只在服务端使用，绝不进入浏览器包。写操作与会话页向 UAM 传 `X-Operator-*`；UAM 本身不校验调用方，访问控制靠网络隔离，服务身份认证留待后续特性。
- 所有写入口（5 个 Server Action 文件与 `POST /logout`）第一步都是 `isSameOriginRequest` 同源校验（`src/lib/security/origin-check.ts`），再读会话、调 UAM。
- **布局不是认证边界**：Next 局部渲染（RSC 请求带的 `Next-Router-State-Tree` 已含 `(shell)` 段）会跳过 `(shell)/layout.tsx`。每个受保护页面第一步调 `requireOperator()`（`src/lib/session/get-session.ts`）：匿名 → 跳登录 / `/session/expire`，unverified → 返回 `null`（页面不渲染、不调 UAM）；`listUsers` / `getUser` / `listOnlineUsers` / `listUserSessions` 在类型上要求传入操作人，并带 `X-Operator-*` 请求头。回归用例见 `tests/e2e/us13-rsc-auth-bypass.spec.ts`。

## 命令（在 `apps/admin-center/` 下执行）

```bash
npm install            # 安装依赖
npm run dev            # 开发服务器
npm run lint           # lint
npm run typecheck      # 类型检查
npm run test           # 单元 / 契约测试
npm run test:cov       # 带覆盖率（行覆盖率 < 90% 则失败）
npm run build          # 生产构建（不需要运行期环境变量）
npm run e2e            # Playwright E2E（需要先启动栈，见下文「E2E 栈」）
npm run gen:api        # 由 contracts/uam/openapi.yaml 重新生成 src/lib/uam/types.generated.ts
```

## 测试与覆盖率

- 测试先行（red → green → refactor）：新行为或缺陷修复先写会失败的测试。
- **覆盖率口径：行覆盖率 ≥ 90%**，工具 Jest 内置覆盖率（`jest.config.ts` 的 `coverageThreshold: { global: { lines: 90 } }`），CI（`.github/workflows/admin-center-ci.yml`）同样执行。
- **排除路径**（`jest.config.ts` 的 `collectCoverageFrom` 里的否定项，只允许放不含业务逻辑的代码，终审在 PR 评审）：`src/components/ui/**`（shadcn/ui 供应商代码）、`src/**/*.generated.ts`（由 OpenAPI 生成）、`src/**/*.d.ts`（类型声明）。其余 `src/**/*.{ts,tsx}`（含 `instrumentation.ts`、`middleware.ts`）都计入。

## CI 与镜像发布

`.github/workflows/admin-center-ci.yml`：`verify`（lint / typecheck / 测试 + 覆盖率 / build）通过后，`image` 任务调用可复用工作流 `.github/workflows/docker-publish.yml`，用 `apps/admin-center/Dockerfile`（上下文为仓库根）构建镜像。

- **触发**：只有 `main` 的 push 才构建并推送到 `registry.cn-shenzhen.aliyuncs.com/s2u2m/admin-center`（阿里云 ACR，仓库需先在 ACR 建好）；PR 与其他分支只跑 `verify`，不构建镜像。
- **tag**：`<版本号>`（取自 `package.json` 的 `version`，如 `0.1.0`）、`<构建号>`（工作流 `run_number`）、`<短 sha>`、`latest`。版本号不变时 `<版本号>` 会被覆盖，要定位确切产物用构建号或 sha。
- **所需 secrets**（仓库 Settings → Secrets and variables → Actions）：`IMG_REPO_USERNAME`、`IMG_REPO_PASSWORD`（阿里云 ACR 的登录用户名与固定密码）。

## 环境变量与示例配置

见 `.env.example`（仅占位值）。真实值只通过部署环境注入，禁止入库。缺少 `UAM_BASE_URL` 时服务**启动**失败（构建不受影响）。

| 变量 | 说明 | 默认 |
|---|---|---|
| `UAM_BASE_URL` | UAM 地址（只在内网可达） | 必填 |
| `UAM_TIMEOUT_MS` | 调用 UAM 的超时 | `1500` |
| `SESSION_COOKIE_NAME` | 会话 cookie 名（本地 http 请用 `asid`） | `__Host-asid` |
| `TRUSTED_PROXY_HOPS` | 取终端来源地址时信任的反向代理跳数（`X-Forwarded-For` 从右数第几项） | `1` |

容器镜像（`apps/admin-center/Dockerfile`，Next.js standalone）监听 `PORT=3000`、`HOSTNAME=0.0.0.0`，以非 root 运行。

## E2E 栈与 Playwright

```bash
devops/local/scripts/bootstrap.sh up      # 构建并启动 PostgreSQL、Redis、UAM、Admin Center，建首个用户；Admin Center 在 http://127.0.0.1:3000
cd apps/admin-center
npx playwright install chromium          # 首次运行前
npm run e2e                              # 全部视口 project（w320 w375 w768 w1024 w1440 w2560）
npm run e2e -- --project=w1440 tests/e2e/us1-login.spec.ts   # 单个视口、单个用例文件
devops/local/scripts/bootstrap.sh down    # 结束后关栈并清数据卷
```

- 配置来自 `devops/local/e2e.env`：**固定的测试配置，仅限本地 E2E**，禁止用于其他环境。首个用户账号与**假的**测试密码见 `tests/e2e/support/fixtures.ts` 的 `ADMIN`（与 `bootstrap.sh` 一致，可用 `E2E_ADMIN_ACCOUNT` / `E2E_ADMIN_PASSWORD` 覆盖）；它们只在本地 E2E 栈里有意义。
- 栈前面没有反向代理，用例统一带 `X-Forwarded-For`（`playwright.config.ts` 的 `extraHTTPHeaders`）；换地址测试用 `E2E_BASE_URL`。部分用例经 `docker compose` 调栈里的 UAM CLI / PostgreSQL 造数（`tests/e2e/support/stack.ts`），需要本机 Docker。

## 多实例、压测与安全检查

- 多实例栈 `devops/local/docker-compose.scale.yml`（3 个 Admin Center + 3 个 UAM + nginx 入口，无会话粘滞）：`devops/local/scripts/scale-checks.sh up|check|down`（入口 `http://127.0.0.1:8088`，钉住实例用 8091 ~ 8093）、`devops/local/scripts/rolling-restart.sh`（持续访问下依次重启全部实例，意外登出必须为 0）。
- k8s 部署（通用清单，本地 kind 与云上共用 `devops/k8s/base`）：本地 `devops/k8s/scripts/deploy.sh up|forward|down`，云上用 `devops/k8s/overlays/cloud`，细节见 [`apps/uam/AGENTS.md`](../uam/AGENTS.md) 的「本地栈」一节。`TRUSTED_PROXY_HOPS` 必须等于前面可信代理的层数（kind 的 Envoy 数据面一层；云上 ingress / Gateway 直面 L4 负载均衡为 1，前面再有 L7 / CDN 则为 2）；云上用默认 `__Host-asid` cookie（要求 HTTPS），kind 的 http 入口用 `asid`；直接 `kubectl port-forward svc/admin-center` 会因为没有 `X-Forwarded-For` 而无法登录。
- 压测：`devops/load/k6/`（`seed-data.sh`、`list-and-detail.js`、`validate-throughput.js`，经 compose 的 `load` profile 运行，命令见 compose 文件里的注释）；结果与未测项见 `devops/load/RESULTS.md`，SC-011 的判定方法见 `devops/load/SC-011.md`（**尚未测量**）。
- **安全检查** `devops/local/scripts/security-check.sh [static|runtime|all]`（默认 `all`；兼容 bash 3.2）：
  - `static`（不需要 Docker）：无被跟踪的 `.env`（只允许 `*.example` 与 `devops/local/e2e.env`）；`package.json` 无数据库驱动 / ORM（FR-013）；工作区与全部历史里没有私钥特征（允许名单与理由写在脚本里）；`src/lib` 服务端模块都有 `import "server-only"`；构建浏览器包（`npm run build`，不设环境变量）后 `.next/static` 里没有服务端机密；每个写入口先做同源校验，并跑相关「跨站 / 同源」单元测试。`SECURITY_CHECK_SKIP_BUILD=1` 复用已有构建。
  - `runtime`（需要已启动的 E2E 栈，否则退出码 2）：用 curl 走真实流程，对 login、createUser、terminateSession、disableUser、enableUser、changePassword、`POST /logout` 各用「外站 Origin / `Sec-Fetch-Site: cross-site` / 无来源头」伪造一次，必须被拒且状态不变（同源请求作对照）；另有 R10：不带 cookie（及带失效 cookie）发 `RSC: 1` + 含 `(shell)` 段的 `Next-Router-State-Tree` 请求数据页，响应里不得出现标记用户名（Next 局部渲染会跳过布局，受保护页面必须自己先调 `requireOperator()`，对照用有效会话）；最后扫描全部响应、两个服务的日志与浏览器脚本，没有标记密码、`$argon2`、会话标识（SC-014）。
  - `all`：先 static 再 runtime；栈原本没起则自己 `up`、结束 `down`，原本已启动则保持原样。退出码 0 全部通过、1 有失败项、2 用法 / 环境错误。

## 运维告警与排障

- **启动失败**「环境变量缺失或不合法：…」：缺 `UAM_BASE_URL`（只列变量名，不回显值）。构建不受影响，所以 CI 的 `npm run build` 不需要这些变量。
- **页面提示「服务暂不可用」**：UAM 不可达或超过 `UAM_TIMEOUT_MS`；写操作则显示「操作未生效」。这时用户没有被登出。
- **本地 http 下登录后立刻回到登录页**：会话 cookie 带 `Secure`，`__Host-` 前缀还要求 HTTPS。本地 http 把 `SESSION_COOKIE_NAME` 设为 `asid`；Chrome / Firefox 对 `localhost`、`127.0.0.1` 放行 Secure cookie，Safari 通常不接受 http 下的 Secure cookie——用 HTTPS 入口或换浏览器。
- **「无法确定终端来源地址，已拒绝登录 / 修改密码」**：`TRUSTED_PROXY_HOPS` 必须等于 Admin Center 前面可信代理的跳数，取 `X-Forwarded-For` 从右数第 N 项（左边用户自带的内容不采信）；直连（无代理）时没有可用来源地址，需要代理或测试里补请求头。入口在非默认端口（非 80 / 443）时，反向代理还必须透传**带端口的** `Host`（nginx 用 `$http_host`）并设 `X-Forwarded-Host`，否则 Server Action 的 Origin / Host 比较失败（见 `devops/load/RESULTS.md` 缺陷 C）。
- **页面时间不是预期时区**：时间在服务端按 Node 进程的本地时区格式化，容器默认 UTC；需要时给 Admin Center 容器设 `TZ`。
- **栈起不来或残留旧数据**：`devops/local/scripts/bootstrap.sh down`（带 `-v` 清卷）后重新 `up`。
