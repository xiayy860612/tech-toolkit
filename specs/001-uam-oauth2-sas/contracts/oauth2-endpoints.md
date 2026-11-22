# Contract: auth 的 OAuth 2.0 / OIDC 端点

标准端点的路径、参数与响应遵循 RFC 6749 / 7636（PKCE）/ 7009（撤销）/ 8414（元数据）与 OIDC Core / RP-Initiated Logout / Back-Channel Logout。此处只写**本服务的约束与自定义部分**；未写之处按标准。`{issuer}` 由部署配置固定，不从请求动态推导。

## 端点一览

| 用途 | 方法与路径 | 说明 |
|---|---|---|
| 授权请求 | `GET /oauth2/authorize` | 未登录时跳转登录页；登录后签发授权码 |
| 登录页 | `GET /login`、`POST /login` | 由 auth 提供（FR-030）；表单字段 `username`、`password` |
| 令牌 | `POST /oauth2/token` | `authorization_code`、`refresh_token` 两种授权类型 |
| 撤销 | `POST /oauth2/revoke` | 需要 client 认证 |
| 用户信息 | `GET /userinfo` | Bearer 访问令牌，需 `openid` scope |
| 元数据 | `GET /.well-known/openid-configuration`、`GET /.well-known/oauth-authorization-server` | |
| 公钥 | `GET /oauth2/jwks` | 发布当前与保留验证的全部公钥（FR-026） |
| 退出 | `GET\|POST /connect/logout` | RP-Initiated Logout；见下 |
| 修改密码 | `GET /account/password`、`POST /account/password` | 已登录用户；字段 `currentPassword`、`newPassword` |
| 健康 | `GET /actuator/health` | 探活（FR-029），不含敏感信息 |

## 授权请求约束（FR-001、FR-001a、FR-005）

- `response_type` 只允许 `code`；`code_challenge` 必填，`code_challenge_method` 只允许 `S256`；`state` 必填；`redirect_uri` 必须与登记值**精确匹配**；`scope` 必须是登记范围的子集。任一不满足：
  - `client_id` 或 `redirect_uri` 无效 → 直接显示错误页，**不重定向**；
  - 其他错误 → 按标准以 `error` 参数重定向回调地址。
- `prompt=login` 或 `max_age` 超过已认证时间 → 忽略 SSO 会话强制重新认证（FR-004）。
- 不展示同意页（FR-006）。
- 授权码：一次性、有效期 60 秒、绑定 client 与回调地址；重复使用 → 拒绝，并删除该授权码已换出的全部令牌（边界情况）。

## 登录行为（FR-007、FR-018）

- 失败统一显示“用户名或密码错误”，对用户不存在、密码错误、账号禁用不作区分；锁定期内的提示与它逐字相同，不让外部判断账号是否存在或是否被锁；登录页固定附一句“多次失败后账号将被临时锁定”。
- `must_change_password = true` 的用户登录成功后，先跳转 `/account/password`，改密后才继续授权流程。

## 令牌

### 访问令牌（JWT，ES256，遵循 RFC 9068）

JOSE 头部 `typ` 为 `at+jwt`，资源服务必须校验该值，避免 ID Token 被当作访问令牌使用。

| 声明 | 内容 |
|---|---|
| `iss` | `{issuer}` |
| `sub` | 用户 id（十进制字符串） |
| `aud` | 目标资源服务的标识；本期只有 auth 自身（管理 API、`/userinfo`），取其 `{issuer}`，后续业务服务各自登记 |
| `client_id` | 签发给的 client（RFC 9068 要求） |
| `scope` | 授予的 scope，空格分隔 |
| `sid` | SSO 会话 id，用于退出通知与关联 |
| `iat` / `exp` / `jti` | 默认有效期 10 分钟，可按 client 配置 |

**不含**：密码、邮箱、手机号、角色清单（FR-010）。管理 API 所需的 `auth.admin` 是 scope，不是资料。

### ID Token

OIDC 标准声明：`iss`、`sub`、`aud`、`exp`、`iat`、`auth_time`、`nonce`、`sid`、`azp`。不含个人资料。

### 刷新令牌

不透明串，每次使用后轮换（FR-011）。刷新时校验：SSO 会话仍有效、用户启用且未因改密失效、client 启用。已使用的刷新令牌再次出现 → 整条授权链删除，返回 `invalid_grant`，写 `REFRESH_REUSE_DETECTED` 审计。

### 用户信息

`GET /userinfo` 仅返回 `sub` 与 `preferred_username`。

## 退出（FR-014、FR-014a）

`/connect/logout` 接受 `id_token_hint`、`post_logout_redirect_uri`（必须在 client 登记的退出后地址中精确匹配）、`state`。执行：

1. 结束当前 SSO 会话；
2. 删除该会话下所有授权（刷新令牌失效）；
3. 对该会话服务过且登记了 `backchannel_logout_uri` 的每个 client，异步发送 back-channel logout（见下）；
4. 重定向到 `post_logout_redirect_uri`（未提供则显示“已退出”页）。

### Back-channel logout 通知（auth → client）

`POST {backchannel_logout_uri}`，`Content-Type: application/x-www-form-urlencoded`，字段 `logout_token`：

```json
{
  "iss": "{issuer}",
  "aud": "<client_id>",
  "iat": 1790000000,
  "jti": "…",
  "sid": "<SSO 会话 id>",
  "events": { "http://schemas.openid.net/event/backchannel-logout": {} }
}
```

不含 `nonce`；ES256 签名。client 返回 `200`/`204` 视为送达，其他或超时（5 秒）按指数退避重试（1s、5s、30s、2min、10min，共 5 次），仍失败记 `GAVE_UP` 并写审计；兜底见 research.md D9。

触发同样的通知的还有：管理员禁用账号、重置密码、强制结束会话，用户改密。

## 错误响应

按标准 `error` / `error_description`；`error_description` 不得包含用户名是否存在、client 内部配置等信息。限流返回 `429`，带 `Retry-After`。
