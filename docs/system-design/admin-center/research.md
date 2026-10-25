# Research（Admin Center）: Admin Center 用户管理与会话管理

> **过程记录（Admin Center）**：原文为 `specs/002-admin-center-uam/research.md`，归档时按 app 拆分：跨服务决策在 [system](../system/research.md)（R1–R6、R11、R18、R20、R23、风险）、UAM 在 [uam](../uam/research.md)（R7–R10、R15–R17、R22）、Admin Center 在 [admin-center](./research.md)（R12–R14、R19、R21）；条目编号 R1…R23 全局不变。当前真实状态以各目录 `README.md` 与代码为准。

**Spec**: [spec.md](../../achieve-specs/admin-center/spec.md) | **Plan**: [plan.md](./plan.md) | **界面原型**: [prototype/](./prototype/README.md)

## 4. 认证与安全（Admin Center 部分）

### R12 Cookie 策略

- **Decision**: 名称默认 `__Host-asid`（开发环境可配置为不带前缀）；属性 `HttpOnly; Secure; SameSite=Strict; Path=/`，不设 `Domain`；`Max-Age` 为剩余绝对寿命。
- **Rationale**: 满足 FR-003 的三项属性，并防止同站子域覆盖。

### R13 CSRF 与写操作入口

- **Decision**: 所有写入口（登录、退出、新建用户、停用、启用、强制下线、改密）都是 POST（Next.js Server Actions 或 Route Handler），Admin Center 对非 GET 请求校验 `Origin` / `Sec-Fetch-Site` 同源；`SameSite=Strict` 作第二层。登录表单同样校验同源以防登录 CSRF。UAM 一侧不处理 cookie，因此不存在 CSRF 面。
- **Rationale**: 满足 FR-033（跨站伪造的写请求 100% 被拒绝，SC-014）。

### R14 Admin Center 的会话三态与 UAM 不可用

- **Decision**: 服务端 `getSession()`：读 cookie → 调 `verify`（超时默认 1.5 秒）→ 三态：`authenticated` / `anonymous`（无 cookie，或 UAM 明确 401）/ `unverified`（超时、5xx、连接失败）。`anonymous` 且曾带 cookie 时经 `/session/expire` 清 cookie（页面渲染期间不能写 cookie）；`unverified` 时受保护页面显示「服务暂不可用」并**保留 cookie**；写操作在 UAM 不可用时提示「操作未生效」，绝不显示成功。每个请求内用 React `cache` 去重，不在 middleware 里发网络请求。
- **Rationale**: 对应 spec 的 FR-009 与 UAM 不可用边界情况。
## 7. 界面与原型

### R19 界面与自适应

- **Decision**: 「顶栏 + 左侧边栏 + 内容区」：桌面侧边栏展开、平板收为图标条、手机为抽屉；用 shadcn/ui `Sidebar`（`collapsible="icon"`）、`Sheet`、`Table`、`Card`、`Badge`、`Dialog` / `AlertDialog`（确认框，自带焦点限制与 `Esc` 关闭）、`DropdownMenu`（顶栏用户菜单）、`Breadcrumb`。列表在手机上变为卡片堆叠（`md` 以下切换）。表单用原生 `<form>` + Server Actions + `useActionState`，服务端 zod 校验；不引入 react-hook-form（YAGNI）。
- **Rationale**: 满足 FR-035 / FR-036；使用成熟的可访问组件以满足键盘与焦点要求。
### R21 界面设计原型的形态

- **Decision**: 原型是**零依赖的静态页面**（`prototype/`，HTML + CSS + 少量 JS，内存假数据），用任何浏览器直接打开：`index.html` 是查看器（顶部有桌面 / 平板 / 手机视口切换与场景选择，把 `app.html` 放进对应宽度的框里），`app.html` 是真实的响应式界面（哈希路由，可点击流转）。场景覆盖 spec FR-041 列出的全部页面与状态。原型**不是**生产代码，不被实现复用；它与 spec 的对应关系见 `prototype/README.md`。原型须经用户确认后才开始界面实现。
- **Rationale**: 评审者无需安装环境（FR-041 / US11）；把「视口」与「状态」做成显式开关，评审时不必手工构造数据。
- **Alternatives**: Figma（需要账号，不在仓库内）；用生产框架搭原型（评审者需要运行环境，且容易被误当成实现）。
