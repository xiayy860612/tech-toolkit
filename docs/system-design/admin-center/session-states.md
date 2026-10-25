# Admin Center — 会话三态与浏览器侧行为（现状归档）

> **归档状态**：特性 `002-admin-center-uam` 已交付并归档。本文是Admin Center 对会话的判定与浏览器侧行为的当前真实状态（技术性内容，故在 `docs/system-design/`；`docs/achieve-specs/admin-center/` 只放用户需求描述 [spec.md](../../achieve-specs/admin-center/spec.md)）。章节编号沿用归档前的合订本，便于对照：本文是原 §5.2；其余见 [需求对照与验证记录](../system/traceability.md)。
> 本文只记录能在代码、测试、脚本或文档中核实的内容。

## 5. 核心业务流程（Admin Center 侧）

### 5.2 Admin Center 侧（会话三态）

| 状态 | 判定 | 表现 |
|---|---|---|
| `authenticated` | `verify` 200 | 正常渲染；顶栏显示用户名 |
| `anonymous` | 无 cookie，或 UAM 401 `session_invalid` | 受保护页面 → `/login?next=<原路径>`；带过 cookie 则经 `GET /session/expire`（重新 `verify` 仍无效才清 cookie）并提示「会话已结束，请重新登录。」（`/login?reason=session_ended`）；若 401 带 `reason: terminated` 则 `/login?reason=session_terminated`，提示「你的会话已被管理员强制下线」；`next` 只接受以单个 `/` 开头的站内路径 |
| `unverified` | 超时（默认 1.5 秒）、5xx、连接失败，或 UAM 拒绝 Admin Center 本身（401 `client_unauthenticated` / 403 `client_forbidden`，即服务密钥 / 权限配置错误） | 受保护页面显示「服务暂不可用」，**保留 cookie、不登出**；写操作提示「操作未生效」，绝不显示成功；后两种拒绝另写告警 `admin-center.uam.misconfigured`；修好后无需重新登录 |

写入口（登录、新建、停用、启用、强制下线、改密、`POST /logout`）第一步都是同源校验（`Origin` / `Sec-Fetch-Site`，失败 403）。退出：UAM 不可用或拒绝时**仍清 cookie 并回登录页**（此时 UAM 侧会话要等到期才失效，写一条不含会话标识的 warn 日志）。
