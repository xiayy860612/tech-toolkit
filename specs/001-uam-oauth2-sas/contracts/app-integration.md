# Contract: App BFF 与 auth-web 的接入约定

给 App1/2/3 BFF 的开发者看：怎么接入 auth service，以及怎么和 auth-web（Portal BFF）配合。App BFF 本身不在本特性范围，这里只定契约。

## 1. 接入前：向管理员申请登记 client

通过管理 API 的 `POST /clients` 登记，需要提供：

| 项 | 要求 |
|---|---|
| `clientId` | 每个应用一个，互不共用（FR-022） |
| `type` | BFF 用 `CONFIDENTIAL`，拿到的 `clientSecret` 只显示一次，自行保管 |
| `redirectUris` | 授权码回调地址，精确匹配，不支持通配 |
| `postLogoutRedirectUris` | 退出后回到哪里，精确匹配 |
| `backchannelLogoutUri` | App BFF 接收退出通知的地址；不提供则不会被通知（退出后最多 10 分钟才发现） |
| `scopes` | 至少 `openid`；要刷新令牌加 `offline_access` |

## 2. 登录：未登录时跳转 auth-web

```text
浏览器 → App BFF（无会话）
App BFF → 302 → auth-web  GET /login?return_to=<App 要回到的完整地址>
auth-web → 授权码流程登录 auth service（auth-web 自己是 client）
auth-web → 302 → return_to
浏览器 → App BFF（仍无会话）→ 发起自己的授权码流程 → auth service（已有 SSO 会话，免输入）→ 回调 → App BFF 建会话
```

`return_to` 约束：

- 必须是 auth-web 配置的白名单来源之一（协议 + 主机 + 端口完全一致）；不在白名单 → auth-web 显示错误页，不重定向（防开放重定向）。
- 路径与查询串原样带回，长度 ≤ 2000。
- auth-web 不会读取、存储或转发 `return_to` 指向应用的任何数据。

App BFF 回到 `return_to` 后**再次**判断到无会话时，直接发起自己的授权码流程，而不是再跳 auth-web，避免循环。

## 3. 授权码流程（App BFF → auth service）

按 [oauth2-endpoints.md](oauth2-endpoints.md)：授权码 + PKCE（S256）+ `state` + `nonce`。令牌只存在 BFF 服务端，浏览器只拿 BFF 自己的会话 cookie（HttpOnly + Secure + SameSite=Lax）。

## 4. 保持登录：刷新与会话生命周期

- BFF 在访问令牌过期前（默认有效期 10 分钟）用刷新令牌换新令牌；刷新令牌每次轮换，**必须保存新的并丢弃旧的**，重用旧的会使整条授权链失效。
- 多个 BFF 实例并发刷新同一个令牌，只有一个成功；失败的实例应重新读取共享存储中的最新令牌，而不是重试旧令牌。
- 刷新返回 `invalid_grant` → 视为已退出：清除本地会话，下次访问按第 2 节重新登录。
- BFF 的本地会话**不得长于** auth service 的 SSO 会话（空闲 30 分钟 / 绝对 8 小时）。

## 5. 退出

- 用户在 App 里点退出：BFF 清除本地会话，并重定向到 auth service 的 `/connect/logout`（带 `id_token_hint`、`post_logout_redirect_uri`、`state`）。
- 其他应用通过 back-channel 通知得知退出：BFF 提供 `backchannelLogoutUri` 对应的端点，校验 `logout_token`（签名、`iss`、`aud`、`events`、无 `nonce`）后，按 `sid` 删除本地会话。使用 Spring Security 的 OIDC back-channel logout 支持即可，注意它要求 BFF 同时配置 `oauth2Login`。
- 通知的格式与重试见 oauth2-endpoints.md「Back-channel logout 通知」。

## 6. 验证令牌（下游资源服务）

- 从 `{issuer}/.well-known/openid-configuration` 取 `jwks_uri`，缓存公钥数分钟；遇到未知 `kid` 时限频刷新一次。
- 校验：签名（ES256）、`iss` 等于配置值（不从令牌取）、`exp`、`aud`。
- 令牌里没有角色与业务权限，授权由各业务系统自己决定（见 spec 的“授权边界”）。

## 7. auth-web 对外提供的端点

| 端点 | 说明 |
|---|---|
| `GET /` | 入口页：未登录显示登录入口，已登录显示当前用户名 |
| `GET /login?return_to=` | 发起登录，成功后回到 `return_to`（见第 2 节）；缺省回到 `/` |
| `POST /logout` | 清除 auth-web 会话并重定向 auth service 的 `/connect/logout`（带 CSRF 令牌） |
| `POST /logout/connect/back-channel/{registrationId}` | 接收 auth service 的退出通知 |
| `GET /actuator/health` | 探活 |

auth-web 自身登记为 client `auth-web`：机密 client，`openid` scope，回调地址 `{auth-web 外部地址}/login/oauth2/code/auth-service`。
