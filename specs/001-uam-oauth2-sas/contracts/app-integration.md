# Contract: App BFF 的接入约定

给 App1/2/3 BFF 的开发者看：怎么接入 auth。App BFF 本身不在本特性范围，这里只定契约。

MVP 不含 auth-web（Portal BFF），各 App BFF 直接向 auth 发起授权请求；auth-web 后续加入时只是一个普通 client，见文末“后续：auth-web”。

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

## 2. 登录：未登录时直接发起授权

```text
浏览器 → App BFF（无会话）
App BFF → 302 → auth  GET /oauth2/authorize（授权码 + PKCE）
auth：有 SSO 会话 → 免输入直接签发授权码；无会话 → 显示登录页，用户输入用户名和密码
auth → 302 → App BFF 的回调地址
App BFF 用授权码换令牌，建立本地会话
```

用户凭据只经过 auth，不经过任何 BFF（FR-030）。

## 3. 授权码流程（App BFF → auth）

按 [oauth2-endpoints.md](oauth2-endpoints.md)：授权码 + PKCE（S256）+ `state` + `nonce`。令牌只存在 BFF 服务端，浏览器只拿 BFF 自己的会话 cookie（HttpOnly + Secure + SameSite=Lax）。

## 4. 保持登录：刷新与会话生命周期

- BFF 在访问令牌过期前（默认有效期 10 分钟）用刷新令牌换新令牌；刷新令牌每次轮换，**必须保存新的并丢弃旧的**，重用旧的会使整条授权链失效。
- 多个 BFF 实例并发刷新同一个令牌，只有一个成功；失败的实例应重新读取共享存储中的最新令牌，而不是重试旧令牌。
- 刷新返回 `invalid_grant` → 视为已退出：清除本地会话，下次访问按第 2 节重新登录。
- BFF 的本地会话**不得长于** auth 的 SSO 会话（空闲 30 分钟 / 绝对 8 小时）。

## 5. 退出

- 用户在 App 里点退出：BFF 清除本地会话，并重定向到 auth 的 `/connect/logout`（带 `id_token_hint`、`post_logout_redirect_uri`、`state`）。
- 其他应用通过 back-channel 通知得知退出：BFF 提供 `backchannelLogoutUri` 对应的端点，校验 `logout_token`（签名、`iss`、`aud`、`events`、无 `nonce`）后，按 `sid` 删除本地会话。使用 Spring Security 的 OIDC back-channel logout 支持即可，注意它要求 BFF 同时配置 `oauth2Login`。
- 通知的格式与重试见 oauth2-endpoints.md「Back-channel logout 通知」。

## 6. 验证令牌（下游资源服务）

- 从 `{issuer}/.well-known/openid-configuration` 取 `jwks_uri`，缓存公钥数分钟；遇到未知 `kid` 时限频刷新一次。
- 校验：签名（ES256）、`iss` 等于配置值（不从令牌取）、`exp`、`aud`。
- 令牌里没有角色与业务权限，授权由各业务系统自己决定（见 spec 的“授权边界”）。

## 后续：auth-web（Portal BFF）

本期不做。它加入时，只需要在 auth 里登记一个机密 client（`openid` scope，回调地址与 `backchannel_logout_uri` 指向 auth-web），再由各 App BFF 在未登录时先跳到 auth-web；auth 不需要任何改动。设计备查见 research.md D10、D15。
