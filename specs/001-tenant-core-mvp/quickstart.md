# Quickstart: Tenant Core MVP 端到端验证

按此指南可验证 spec 的三个用户故事与 Internal JWT 能力。具体构建/测试命令以子项目 `AGENTS.md` 为准（实现阶段补齐），此处只描述验证场景与预期。契约见 [contracts/cli.md](contracts/cli.md)、[contracts/tenant-core-api.yaml](contracts/tenant-core-api.yaml)，表结构见 [data-model.md](data-model.md)。

## 前置

1. 启动本地依赖（Postgres、Redis）：`deploy/docker-compose.yml`（含创建应用角色 `tenant_core_app` 的初始化脚本）。
2. 配置环境变量（参照各子项目的 `.env.example`，不入库）：数据库连接、Redis 地址、BFF 的 `tenant-core-api` 地址、`UAM_ISSUER`、`UAM_AUDIENCE`、`UAM_CELL_ID`、`UAM_SIGNING_KEY_PEM`（本地可用 `dev` profile 生成临时密钥）。
3. 构建并启动 `tenant-core-api`（自动执行 Flyway 迁移）和 `apps/platform-web`。CLI 以可执行产物 `tenant-core-cli <命令>` 调用。

## 场景 A：CLI Bootstrap（Story 1）

依次执行（密码在提示时输入，例如 14 位以上的测试口令）：

1. `tenant-core-cli tenant create --slug acme --name "Acme Corp"` → 预期 `created`，退出码 0。
2. `tenant-core-cli login-method enable --tenant acme --type password` → `enabled`。
3. `tenant-core-cli user create --tenant acme --display-name "张三"` → `created`，输出 User ID `tu_…`（此时该 User 还不能登录）。
4. `tenant-core-cli account create --tenant acme --user <tu_…> --username admin` → 隐藏输入并确认密码 → `created`。
5. `tenant-core-cli subscription add --tenant acme --module platform` → `subscribed`。
6. `tenant-core-cli tenant show --tenant acme` → 能看到 Login Method `password`、User“张三”及其 Account `admin`、订阅 `platform`。

**幂等**：把 1–5 各再执行一次 → 均退出码 0，提示已存在，`tenant show` 结果不变（SC-004）。
**失败路径**：对不存在的 Tenant 执行 2/3/4/5 → 退出码 2；`account create` 时未启用密码登录或 User 不存在 → 退出码 2；密码少于 12 位 → 退出码 1。
**保密**：检索 stdout、应用日志、`audit_event` 中的测试口令 → 0 命中（SC-005）。

## 场景 B：Web 登录（Story 2）

1. 分别打开三个受保护页 → 都被引导到登录页（SC-007）。
2. 用 `acme / admin / <初始密码>` 登录 → 因首次登录被带到改密页，改密前访问其他页面均回到改密页。
3. 设置新密码（≥ 12 位）→ 进入主界面。
4. 错误密码与不存在的 Account 分别登录 → 提示文案一致（SC-006）。
5. 对未订阅 `platform` 的 Tenant：用**错误**密码登录 → 仍是统一提示；用**正确**密码登录 → 提示无访问权限。
6. 连续 5 次错误密码 → 之后即使输入正确密码，15 分钟内仍提示用户名或密码错误且文案含“请稍后再试”。
7. 退出登录 → 再访问受保护页回到登录页。
8. Token 在后端失效（如强制吊销）后再点击任一导航 → 被清除 Session 并回到登录页。

## 场景 C：导航与只读列表（Story 3）

登录后：

1. 左侧恰好三项：租户管理、模块管理、用户管理，当前项高亮。
2. 租户管理 → 看到 `acme`（标识、名称、状态、创建时间）。
3. 模块管理 → 看到 `platform`（所属 Tenant acme、状态、订阅时间）。
4. 用户管理 → 看到 User“张三”及其 username `admin`（所属 Tenant、状态、创建时间），无任何 Credential 信息；一个尚无 Account 的 User 也出现在列表中。
5. 页面上无新增/编辑/删除入口。
6. 用 CLI 再造 500 个 User（脚本循环）→ User 页分页可用，首屏 ≤ 2 s（SC-008）。

## 场景 D：Internal JWT（FR-023）

用场景 B 登录得到的 Opaque Token（仅测试环境可从 BFF session 或直接调 `POST /v1/auth/login` 得到）：

1. `POST /v1/auth/exchange`（请求头 `X-Session-Id: <Opaque Token>`）→ 返回 Internal JWT；其 `sub` 为 `tu_…`，`exp - iat` 为 120 秒。
2. `GET /.well-known/jwks.json` → 含 ES256 公钥与 `kid`；用它验证上一步的 JWT 签名通过。
3. 用 Internal JWT 经 `Authorization: Bearer <Internal JWT>` 调 `GET /v1/auth/me`、`GET /v1/tenants` → 200。
4. 用 Internal JWT 调 `POST /v1/auth/logout` 或 `/v1/auth/change-password` → 被拒绝（仅接受 Opaque Token）。
5. 同时带 `X-Session-Id` 与 `Authorization: Bearer` → 401；在 Bearer 里放 Opaque Token、或在 `X-Session-Id` 里放 Internal JWT → 401。
6. 退出登录后再换发 → 失败；已换发的 JWT 在 120 秒内仍可访问列表，过期后 401。
7. 篡改签名、错误 `iss`/`aud`、`alg=none`、HS256 → 均 401。
8. 仍需改密的 Session 尝试换发 → 被拒绝。

## 场景 E：自适应与中文界面（FR-024、FR-025）

在 360px、768px、1280px 三种宽度（浏览器开发者工具或调整窗口）下登录并浏览：

1. 1280px：左侧导航 + 表格；列头为中文（显示名、用户名、租户、状态、创建时间），状态为“正常 / 已订阅”，时间为本地时区，无账户的用户名显示“—”。
2. 768px：仍是左侧导航；< 768px：变成顶部栏，点菜单按钮展开三个导航项。
3. 360px：三个列表为卡片，页面无横向滚动。
4. 改密页输入弱密码：只看到中文规则提示。
5. 登录页与改密页居中；输入框和按钮高度不小于 44px。

## 场景 F：顶栏、用户菜单与页头（FR-026、FR-027、FR-028）

1. 登录后任意列表页：顶部有“Platform”产品名，右侧有显示当前 User Display Name 的用户菜单按钮。
2. 点击用户菜单：看到 Display Name、username、Tenant Slug 和“退出登录”；点击“退出登录”回到登录页。
3. 侧边栏（≥ 768px）只有三个导航项，底部没有“退出登录”；窄屏的折叠菜单里也没有。
4. 三个列表页的页头：左侧标题，右侧操作位为空；页面中没有任何写操作控件。
5. 360px、768px、1280px 下重复 1–2：顶栏始终可见，用户菜单可点击，页面无横向滚动。

## 场景 G：前端性能预算（FR-029、SC-011）

1. 启动完整栈（生产构建的 web）并灌入 500 条 User。
2. 运行 `pnpm perf`：对登录页、改密页和三个列表页，在移动端与桌面配置下各测 5 次。
3. 打开报告：每页每项指标的中位数都在 FR-029 目标内；INP 交互项（用户菜单、Sheet 菜单、导航点击）≤ 200 ms；每路由首次加载 JS ≤ 170 KB。
4. 未达标项逐条给出原因与处理（优化或经评审放行）。

## 自动化对应

| 场景 | 自动化层级 |
|---|---|
| A | CLI 契约测试 + Testcontainers 集成测试 |
| B 的接口行为 | `tenant-core-api` 契约/集成测试（含锁定、时序、改密、订阅顺序） |
| B/C 的页面行为 | 前端组件测试 + Playwright 旅程（CLI 数据 → 登录 → 改密 → 三页可见） |
| D | `InternalJwtServiceTest`、`TokenExchangeIT`、`AuthApiContractIT` |
| E | 组件测试（DOM 与断点类名）+ Playwright 三种视口（无横向滚动、菜单可用、卡片可见） |
