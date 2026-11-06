# 02 认证与 token 体系

状态：设计基线（尚未落地）。本文按 [06-decisions.md](06-decisions.md) 的 D16 到 D26 重写，是 UAM、网关 ext_authz、BFF 及各服务验签实现的根本依据。

> 本文对应的图（`assets/02`、`03`、`06`、`07`）仍是旧的 Keycloak 方案，待重画，在重画之前不要作为依据。

## 1. 原则

1. **终端只持有不透明令牌**，由 UAM 签发；令牌本身不含任何信息，服务端查表得到身份。
2. **网关统一换发**：各 cell 网关的 ext_authz 校验不透明令牌，换成短有效期的内部 JWT 转发；内部 JWT 不出集群。
3. **服务仍每一跳校验**：各服务用 JWKS 本地验签内部 JWT，不因为在内网就信任，也不回调 UAM。
4. **登录方式与令牌解耦**：UAM 把各种登录方式归一成“已验证身份”，之后的令牌签发、网关、下游完全不关心用户是怎么登录的。
5. **各 cell 信任根分开**：每个 cell 各有自己的网关、UAM、`iss` 与签名密钥；一个 cell 的服务只信本 cell 的 UAM，因此租户 cell 与平台 cell 的令牌互不通用。
6. **授权不在 token 里**：token 只证明“你是谁、从哪来、在哪个租户”，角色、租户状态、已开通模块由 Tenant Core 提供。

内部 JWT 不放 email、姓名等资料，因为它会被透传到每个内部服务和日志里。

## 2. 多端接入

进入内部之后统一成同一种内部 JWT，差别只在终端持有的凭证。

| 端 | 第一阶段 | 凭证存放 | 要点 |
|---|---|---|---|
| Web（SSR） | 经 BFF 用 UAM 登录，BFF 持有不透明令牌 | 浏览器只有 HttpOnly + Secure + SameSite 的 session cookie；令牌放在 Redis 里的 session | 令牌不到浏览器；BFF 无状态化（session 在 Redis）才能水平扩展 |
| 桌面端 | 通过系统浏览器登录 UAM 的登录页，回调拿到不透明令牌 | 系统 Keychain / Credential Manager | public client，不保存 secret；回调走 loopback 或自定义 scheme；refresh 令牌开启轮换 |
| 外部服务（M2M） | **后期**，随 broker 接入（见第 9 节） | | 第一阶段不支持外部服务接入 |

staff 与租户用户走同一条路径：Platform BFF 与 Tenant BFF 都带着不透明令牌经网关调用，BFF 不接触内部 JWT（D25）。

## 3. UAM

### 3.1 定位

UAM（User Account Management）是独立自研的 Auth 服务，负责“你是谁”并签发凭证：

- 提供登录（第一阶段是用户名密码），管理用户、登录身份与凭证；
- 签发、刷新、吊销不透明令牌；
- 向网关提供令牌校验所需的数据，并提供内部 JWT 的 JWKS；
- 承载 IdP、登录方式的管理（原 Auth Admin 门面的职责，D16）。

它不负责业务授权，也不保存租户用户资料与 RBAC（Tenant Core）、租户注册与模块授权（Platform Service）。

前期 UAM 与 Tenant Core 合并部署为一个服务（D29），上面的“不负责”指模块边界：认证模块不读写档案与 RBAC 的表，反之亦然。

### 3.2 部署

同一份代码，每个 cell 一套部署（D16、D26、D28）：

| 部署 | 服务对象 | 隔离 |
|---|---|---|
| 平台 UAM（平台 cell） | 平台租户（platform_staff） | 独立库、独立 `iss`、独立签名密钥 |
| 租户 UAM（每个租户 cell 一套） | 该 cell 内的租户用户；pool cell 里是多个租户，用 `tenant_id` 做逻辑隔离：表带 `tenant_id`，Postgres 行级安全，应用使用受限数据库角色 | 每套有独立的库、`iss` 与签名密钥 |

silo 租户是独占的 cell，天然有独占的 UAM。租户注册表的“UAM 部署指向”指向租户所属 cell 的 UAM。

### 3.3 数据模型

| 对象 | 内容 |
|---|---|
| tenant | 在 UAM 里的租户记录：`tenant_id`、状态、各登录方式的配置。平台租户是保留租户，租户管理接口不能操作 |
| user | `tenant_user_id`、`tenant_id`、状态（启用、禁用） |
| identity | 属于某个 user，一个用户可以有多条。类型（`password`、`oidc`、`wechat` 等）、提供方、主体、是否已验证。唯一键是 (租户, 类型, 提供方, 主体) |
| credential | 密码哈希等凭证，与 `password` 类型的 identity 关联，单独存放 |
| token | 不透明令牌的哈希、类型、所属用户与终端、`azp`、`sid`、过期时间、吊销状态 |

### 3.4 Authenticator 接口

UAM 以 `Authenticator` 接口承载登录方式（D17）：输入登录请求，输出统一的“已验证身份”（类型、提供方、主体、已验证属性）。

| Authenticator | 阶段 |
|---|---|
| `PasswordAuthenticator` | 第一阶段 |
| `OidcAuthenticator`（租户自带 IdP、Keycloak 等 broker） | 后期 |
| `WeChatAuthenticator` | 后期 |
| broker token 校验（外部服务 M2M） | 后期（见第 9 节） |

已验证身份交给“身份解析”：按 (租户, 类型, 提供方, 主体) 找到 user，之后的令牌签发与登录方式无关。新增登录方式只新增 Authenticator，不改令牌、网关和下游。

### 3.5 Admin 职责

- 创建、禁用用户，重置密码，管理用户的登录方式；
- 令牌吊销（按令牌、会话、用户、租户）；
- 响应租户开通事件，创建租户记录与登录配置；
- 为 Tenant BFF 提供管理接口（租户管理员经 BFF 调用，不直接访问 UAM）；
- 配置变更写审计；
- CLI 冷启动（见 6.5）。

## 4. 令牌

### 4.1 不透明令牌

- 高熵随机串，带类型前缀（如 `tk_`），方便泄漏扫描；库里只存哈希。
- 有效期、刷新与轮换方式待定（见 06）；原则：UAM 在签发与刷新时检查用户与租户状态，禁用或暂停即拒绝。
- 桌面端使用 refresh 令牌并开启轮换；Web 由 BFF 持有，按过期前主动刷新，多个 BFF 实例并发刷新要加锁或幂等。
- 吊销：删除令牌记录，网关下一次校验即拒绝，延迟取决于 ext_authz 的缓存时长（待定）。

### 4.2 内部 JWT 契约

```json
{
  "iss": "https://uam.tenant.example.internal",
  "aud": "tenant-services",
  "sub": "tu_8f3a...",
  "exp": 1762000120,
  "iat": 1762000000,
  "jti": "b2c1...",

  "tenant_id": "t_1024",
  "cell_id": "pool-1",

  "scope": "order:read order:write",

  "client_type": "web",
  "azp": "tenant-admin-bff",
  "sid": "s_77e0..."
}
```

| 分组 | 字段 | 说明 |
|---|---|---|
| 标准 | `iss`、`aud`、`exp`、`iat`、`jti` | `iss` 是所属 UAM 部署的标识；`exp` 很短（建议 1 到 5 分钟，待定）；`aud` 是否区分平台与租户待定（见 06） |
| 主体与租户 | `sub`、`tenant_id`、`cell_id` | `sub` 就是 `tenant_user_id`，下游只用它（D8）；`tenant_id` 来自 UAM 的认证配置，是租户隔离的唯一可信来源；`cell_id` 标识所在 cell，用于审计与排查，路由在 cell 之外完成（见 04） |
| 授权 | `scope` | 只放终端级的粗粒度范围，不放角色 |
| 来源 | `client_type`、`azp`、`sid` | 由 UAM 按实际登录流程写入，不取客户端自述；`client_type` 为 `web` 或 `desktop`，后期有 `service` |

没有 `domain` 声明：平台与租户用不同的 `iss` 与密钥区分（D26）。平台 token 的 `cell_id` 指平台 cell。

`amr`（本次登录方式）是否写入待定。

### 4.3 角色的获取

token 只证明“你是谁、从哪来、在哪个租户”。用户的角色、租户状态和已开通模块由 Tenant Core 提供：

- 业务服务按 `sub`（`tenant_user_id`）向 Tenant Core 查询，属于向下的同步调用，符合分层规则；
- 结果在服务内短 TTL 缓存；
- 角色变更对新请求立即生效，不必等 token 过期；
- 代价：业务服务多一个对 Tenant Core 的调用，该接口需要低延迟、高可用。

接口的返回内容、缓存 TTL、失效方式、Tenant Core 不可用时的降级行为为**待定**（见 06）。

### 4.4 来源的判定

来源信息的用途：

- 收窄权限：同一用户从桌面端和 Web 登录，`scope` 可以不同。
- 审计：记录 `client_type`、`azp`、`sid`，事后能还原是谁、从哪类终端、哪个会话发起。

业务服务里尽量少写“如果是某个终端就怎样”的分支，终端差异应在边界转换成 `scope`，用户的角色与权限由 Tenant Core 提供。

## 5. 网关与换发

每个 cell 网关的 ext_authz 是进入该 cell 的每个请求的必经环节（D18、D28）：

1. 取出 `Authorization: Bearer` 中的不透明令牌；
2. 查令牌记录（Redis 加本地短缓存，具体方式待定），检查未过期、未吊销，用户与租户状态为启用；
3. 取或生成该令牌对应的内部 JWT：按令牌缓存，临近过期再重签，避免每个请求都签名；
4. 用内部 JWT 替换 `Authorization`，转发给本 cell 的服务；
5. 任何一步失败都拒绝（失败即拒绝）。

要点：

- ext_authz 要低延迟、高可用，可水平扩展；
- 内部 JWT 的签名密钥放在 ext_authz 进程内（由 KMS 加密保存），不每次调用 KMS；各 cell 的密钥分开；
- 各服务按配置里固定的 `iss` 取 JWKS 验签、检查 `aud` 与 `exp`，不根据 token 动态拉取；
- 前期不做通用限流；UAM 登录端点的失败锁定属于例外（D22）。

## 6. 登录与用户

### 6.1 密码登录

UAM 自己存密码（D22）：argon2id 哈希；密码策略；连续失败锁定，登录端点限流；找回密码与通知经 Notification；凭证不进日志、不出 UAM。具体的策略、阈值与找回流程待定（见 06）。

### 6.2 用户的创建

用户由 UAM 创建（D20）：

- UAM 生成 `tenant_user_id` 并保存，发出“用户已创建”事件；Tenant Core 以该 ID 幂等地异步建档案，新用户没有任何角色，权限查询按“无角色”处理。前期 UAM 与 Tenant Core 合并部署时，档案在同一事务内创建，事件在进程内传递（D29）。
- 第一阶段用户来自 CLI 冷启动与管理员创建，不做 JIT。管理员经 Tenant BFF 调用 UAM 的管理接口创建用户，初始密码的交付方式待定。
- 禁用或删除：在 UAM 操作，UAM 发出事件并吊销该用户的全部令牌，Tenant Core 同步状态。

### 6.3 JIT（后期）

接入外部 IdP 后，可按租户与 IdP 的开关，允许登录时自动创建用户，并遵守：

1. 租户来自校验所用的认证配置，绝不取自外部声明；
2. 绑定键是 (租户, 类型, 提供方, 主体)，不用全局 `sub`，不按邮箱自动合并；
3. 新用户默认无角色；
4. 创建幂等，并发首次登录只建一个用户；
5. 租户暂停或用户禁用时拒绝签发；
6. 只对已验证域名的 IdP 允许按邮箱关联已有用户。

### 6.4 多种登录方式关联到同一用户（D21）

- 新增登录方式必须在已登录状态下进行，且新方式自己也要通过验证，不能凭邮箱相同自动合并；
- 解绑时至少保留一种可用登录方式；
- 一个外部身份只能绑定一个用户，绑定时检查，防止被抢占。

### 6.5 CLI 冷启动

CLI 只用于初始化（D23）：

- 平台 UAM：第一个 staff；租户 UAM：初始租户与第一个租户管理员；平台租户及其模块授权也由 CLI 写入。
- 初始密码不作为命令行参数，改为交互输入、标准输入或一次性链接，首次登录强制改密。
- 幂等；只能在受控环境运行（如 Kubernetes Job）；执行记入审计。
- 日常开通租户走 Platform Service 的事件，租户注册表以 Platform Service 为准；CLI 写入的记录要与之对账（方式待定）。

### 6.6 退出与吊销

- 用户退出：BFF 删除自己的 session，并通知 UAM 吊销对应令牌；
- 管理员强制下线、禁用用户或暂停租户：UAM 删除相应令牌，网关下一次校验就拒绝；BFF 的 session 在下一次请求时发现令牌失效而清除；
- 已换发的内部 JWT 最多再存活到它的 `exp`，这是可接受的吊销延迟。

## 7. 用户与会话的归属

| 对象 | 内容 | 归属 |
|---|---|---|
| user、identity、credential | 登录凭证与身份绑定 | UAM |
| tenant_user 档案 | 资料、成员关系、角色与 RBAC、启用/禁用 | Tenant Core（平台租户的档案在平台 cell 的 Tenant Core） |
| BFF 应用会话 | HttpOnly cookie 对应的 Redis session，存不透明令牌 | Platform BFF / Tenant BFF |
| UAM 令牌与会话 | 不透明令牌、refresh 令牌 | UAM |
| 内部 JWT | 1 到 5 分钟内有效的无状态 token | 不存储，只在网关缓存 |

## 8. 密钥与 JWKS

- 每套 UAM 部署有自己的签名密钥与 JWKS，只在集群内网提供，用 NetworkPolicy 限制访问；算法用 ES256 或 RS256，不用 HS256。
- 签名密钥由 ext_authz 在进程内使用，用 KMS 加密保存（见第 5 节）。
- 轮换要有重叠期：先加入新公钥，下游缓存刷新后再切换签名，旧公钥至少保留一个内部 JWT 最大有效期后再删除。
- 各服务按配置里预先写好的 `iss` 取对应 JWKS，不根据 token 里的 `iss` 动态拉取；本地缓存数分钟，未知 `kid` 时限频刷新一次；UAM 短暂不可用时继续用缓存。

Spring Boot 的 Resource Server 只接受本 cell 的 UAM：

```java
JwtIssuerAuthenticationManagerResolver resolver =
    new JwtIssuerAuthenticationManagerResolver(trustedIssuers::contains);
// trustedIssuers 由配置给出，各 cell 只含本 cell UAM 的 iss
```

## 9. 后期：broker、外部 IdP 与外部服务

第一阶段不引入 broker（Keycloak 等）。以下作为新的 Authenticator 或接口接入，不影响令牌契约与下游（D17、D24）：

- **租户自带 OIDC IdP**：每租户可配置多个，由 `OidcAuthenticator` 对接，配置经 UAM 管理，须防 SSRF（只允许 https，拦截内网与保留地址，设超时与响应大小上限）；租户必须保留至少一种可用登录方式，IdP 禁用而不是删除，禁用时吊销该 IdP 登录产生的令牌。
- **broker**：登录由 broker 完成，UAM 验 broker 签发的 token，按第 6.3 节解析身份。
- **外部服务（M2M）**：先在 broker 登录，UAM 验 broker 的 token，再换不透明令牌；登记的权限范围以 UAM 为准，不取 broker 的声明；令牌的 `client_type` 为 `service`，没有对应的用户。
- **微信**：实现 `WeChatAuthenticator`。

## 10. 完整交互

以租户用户通过 Web 用密码登录并调用业务接口为例：

```
浏览器 ──登录页提交用户名密码──▶ Tenant BFF ──▶ 租户 UAM
UAM：PasswordAuthenticator 验证 → 解析 user → 签发不透明令牌
Tenant BFF：把令牌存入 Redis session，返回 session cookie

浏览器 ──cookie──▶ Tenant BFF：取出令牌
Tenant BFF ──Bearer 不透明令牌──▶ 本 cell 网关的 ext_authz
ext_authz：校验令牌 → 换内部 JWT
gateway ──内部 JWT──▶ 业务服务：本地验签 → 向 Tenant Core 查角色与已开通模块 → 处理
```

其他场景沿用同一套结构：

- staff 登录：Platform BFF 对平台 UAM 登录，请求同样经网关的内部 listener 换发（D25），服务验平台 UAM 的 `iss`。
- 桌面端：在系统浏览器里登录 UAM，回调拿到令牌，直接带它调用网关。
- 令牌刷新：BFF 或桌面端用 refresh 令牌向 UAM 刷新，UAM 检查用户与租户状态，并轮换 refresh 令牌。
- 服务间调用：用户发起时透传内部 JWT；定时任务、事件消费者用 service token（见 03）。

## 11. 待定项

均记录在 [06-decisions.md](06-decisions.md) 第 3 节，包括：令牌有效期与刷新、ext_authz 的查令牌方式与缓存时长、内部 JWT 签名密钥的存放与轮换、管理员创建用户的入口与初始密码交付、密码策略与找回、桌面端登录流程、租户 UAM 的行级安全方案、`amr` 与 `aud` 是否分开、broker 选型与 M2M 流程，以及 Tenant Core 权限查询接口的细节。
