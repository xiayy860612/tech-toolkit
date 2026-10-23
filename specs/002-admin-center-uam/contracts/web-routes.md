# Contract: Admin Center 页面、路由与 cookie

**Feature**: [spec.md](../spec.md) | **UAM 契约**: [contracts/uam/openapi.yaml](../../../contracts/uam/openapi.yaml) | **Date**: 2026-10-23

Admin Center 是接入层 Web Server（Next.js SSR）：浏览器对它的访问**基于会话**；它对 UAM 的访问**不基于会话**（见 openapi.yaml）。本文约定页面、路由、cookie 与错误文案，是 Admin Center 的前端 / 路由契约。

## 1. 页面与路由

| 路由 | 访问 | 说明 |
|---|---|---|
| `/login` | 匿名 | 登录页（极简外壳，居中卡片）。已登录访问则重定向 `/users` |
| `/` | 登录 | 重定向到 `/users`（默认页面） |
| `/users` | 登录 | User Manager 列表：搜索（`?q=`）、分页（`?page=`），「新建用户」入口 |
| `/users/new` | 登录 | 新建用户表单 |
| `/users/[userId]` | 登录 | 用户详情：基本信息、全部账号、停用 / 启用 |
| `/sessions` | 登录 | Session Manager：在线用户列表，分页（`?page=`） |
| `/sessions/[userId]` | 登录 | 该用户的当前会话，含「强制下线」 |
| `/account/password` | 登录 | 修改自己的密码 |
| `POST /logout` | 登录 | 退出：调 UAM `logout`、清 cookie、回 `/login` |
| `GET /session/expire` | 内部 | 重新 `verify` 仍无效后清除失效 cookie 并 303 到 `/login`；页面渲染期间不能写 cookie，故单设此路由 |

- 未登录访问受保护页面：重定向 `/login?next=<原路径>`；`next` **只接受以单个 `/` 开头的站内路径**（拒绝 `//`、带协议、含反斜杠），否则忽略并进入 `/users`（FR-012）。
- 受保护页面共用外壳：顶栏（品牌、手机菜单按钮、用户菜单含「修改密码」「退出登录」）+ 左侧边栏 + 面包屑 + 内容区。侧边栏导航项：**用户管理**（`/users`）、**会话管理**（`/sessions`）；桌面展开、平板图标条、手机抽屉；展开偏好存非敏感 UI cookie `sidebar_state`。
- 不存在的 `userId`：渲染「用户不存在」（FR-019）；格式非法的 `userId` 同样处理。

## 2. 会话 cookie

| 项 | 约定 |
|---|---|
| 名称 | 默认 `__Host-asid`（开发环境可配置为不带前缀） |
| 内容 | 机密的会话标识（`login` 响应中的 `sessionId`）。不含任何可读数据 |
| 属性 | `HttpOnly; Secure; SameSite=Strict; Path=/`，不设 `Domain`；`Max-Age` = `maxAgeSeconds` |
| 写入 | 仅登录成功时 |
| 清除 | 退出、`/session/expire`、停用 / 强制下线后下一次请求被判未登录时 |
| UAM 不可用 | **不清除**（FR-009） |

会话数据（`verify` 返回的用户、账号、会话引用）**只存在于 Admin Center 服务端的单次请求内存**：每个请求先 `verify` 得到操作人，用于本请求内对 UAM 的调用（作为 `X-Operator-User-Id` / `X-Operator-Account-Id` / `X-Operator-Session-Ref` 请求头传入）；不写 cookie、不返回浏览器、不写日志。UAM 不使用任何用户凭据。

## 3. 会话三态

`getSession()`（服务端，请求内缓存）：

| 状态 | 判定 | 页面表现 |
|---|---|---|
| `authenticated` | `verify` 返回 200 | 正常渲染，顶栏显示用户名 |
| `anonymous` | 无 cookie，或 `verify` 返回 401 `session_invalid` | 受保护页面 → `/login`；若带过 cookie 则经 `/session/expire` 清除，并在登录页提示「会话已结束，请重新登录」 |
| `unverified` | `verify` 超时（默认 1.5 秒）、5xx、连接失败，或 UAM 返回 401 `client_unauthenticated` / 403 `client_forbidden`（服务密钥配置错误） | 受保护页面显示「服务暂不可用，请稍后重试」，**保留 cookie** |

## 4. 写操作（Server Actions / POST）

| 动作 | 入口 | 调用 UAM | 成功后 |
|---|---|---|---|
| 登录 | `/login` 表单 | `POST /v1/auth/login` | 写 cookie；跳 `next` 或 `/users` |
| 退出 | `POST /logout` | `POST /v1/auth/logout` | 清 cookie；`/login` |
| 新建用户 | `/users/new` 表单（每次渲染生成 `Idempotency-Key`） | `POST /v1/users` | 303 到 `/users/{id}`，提示「用户已创建」 |
| 停用 / 启用 | `/users/[userId]` 确认框 | `POST …/disable` / `…/enable` | 留在详情页，状态与提示更新 |
| 强制下线 | `/sessions/[userId]` 确认框 | `DELETE /v1/sessions/{sessionRef}` | 留在会话页，该会话消失；用户已无会话则提示并返回 `/sessions` |
| 修改密码 | `/account/password` 表单 | `POST /v1/accounts/{accountId}/password`（`accountId` 取自操作人自己的账号） | 留在页面，提示「密码已修改」；当前会话保持有效 |

所有写入口：同源校验（`Origin` / `Sec-Fetch-Site`，失败 403）；会话非 `authenticated` 时按三态处理；UAM 不可用时提示「操作未生效」，**绝不显示成功**；重复提交由 `Idempotency-Key` 与按钮禁用共同防护。

## 5. UAM 错误码 → 界面文案

| UAM `code` | 出现位置 | 文案 |
|---|---|---|
| `invalid_credentials` | 登录 | 账号名或密码错误 |
| `throttled` | 登录 / 修改密码 | 尝试过于频繁，请 `{Retry-After}` 秒后再试 |
| `session_invalid` | 任意受保护请求 | 会话已结束，请重新登录（跳登录页） |
| `account_name_taken` | 新建用户 | 账号名已被使用（字段旁，保留已填内容，不含密码） |
| `validation_failed` | 新建用户 / 修改密码 | 按 `errors[].field` 与 `reason` 在字段旁给出明确原因 |
| `cannot_disable_self` | 停用 | 不能停用当前登录的用户（入口已禁用并说明，服务端同样拒绝） |
| `cannot_disable_last_active_user` | 停用 | 不能停用最后一个正常用户（同上） |
| `cannot_terminate_own_session` | 强制下线 | 这是你当前使用的会话，请改用「退出登录」 |
| `session_not_found` | 强制下线 | 该会话已结束，已刷新为最新状态 |
| `user_not_found` | 详情 / 会话 | 用户不存在 |
| `current_password_incorrect` | 修改密码 | 当前密码不正确 |
| `weak_password` / `same_as_current` | 修改密码 | 新密码不满足强度要求 / 不能与当前密码相同 |
| `client_unauthenticated` / `client_forbidden` | 任意 | 读：服务暂不可用，请稍后重试；写：操作未生效；**不登出用户、不清 cookie**，并写运维告警日志（UAM 拒绝了 Admin Center，通常是服务密钥配置错误） |
| 超时、5xx、连接失败 | 任意 | 读：服务暂不可用，请稍后重试；写：操作未生效 |

## 6. 响应头与浏览器侧安全

- CSP（含 nonce）、`X-Content-Type-Options: nosniff`、`Referrer-Policy: same-origin`、`frame-ancestors 'none'`，由 middleware 设置；middleware **不发网络请求**。
- Admin Center 对 UAM 的每个请求都带 `Authorization: Bearer <UAM_SERVICE_KEY>`；缺少该环境变量时服务启动失败。
- 页面、响应与日志中不出现密码、密码哈希、会话标识；表单密码字段为 `type=password`、`autocomplete` 设为 `current-password` / `new-password`；失败回显时**不回填密码**。
- 服务密钥（`UAM_SERVICE_KEY`）与任何与 UAM 通信相关的令牌**只在服务端**：`src/lib` 下的服务端模块一律 `import "server-only"`，由安全检查脚本核对浏览器包。

## 7. 响应式契约（FR-035、FR-036）

| 视口 | 外壳 | 列表 | 表单 / 对话框 |
|---|---|---|---|
| 桌面 ≥ 1024 | 侧边栏展开 | 表格 | 内联表单；确认对话框居中 |
| 平板 768–1023 | 侧边栏图标条（悬停 / 聚焦显示名称） | 表格 | 同上 |
| 手机 < 768 | 侧边栏收为抽屉（顶栏菜单按钮，焦点限制，`Esc` 关闭并回到按钮） | 卡片堆叠，关键信息不丢失，操作不依赖悬停 | 单列；对话框可滚动，确认与取消始终可达 |

320–2560 宽度内无整页横向滚动；触控目标 ≥ 44 × 44 CSS 像素，破坏性按钮与相邻按钮留有间距；仅键盘可完成全部操作，焦点清晰可见。
