# 06 决策记录与待定项

状态：设计基线（尚未落地）。记录关键决策、前期省掉的功能及其触发条件、仍待决定的事项。待定项定下来后，把结论写进对应的主题文档，并从这里删除。

> 本文已按 UAM 方案更新（D16 起）。01、02、03、04 及相关图尚未同步，在它们更新之前，**以本文为准**；与本文冲突的旧描述（Keycloak、realm、Auth Admin 门面、Back-Channel Logout、边界直接验 broker token 等）视为已被替换。

## 1. 关键决策

| # | 决策 | 理由 | 状态 | 详见 |
|---|---|---|---|---|
| D1 | 平台侧与租户侧分库、分审计，互不同步调用，只通过 Event Bus 交互 | 租户内部数据对平台侧不可见；故障与权限边界清晰 | 有效 | 01、03、05 |
| D2 | 四层分层，同步调用只向下，同层与向上调用改用事件 | 避免循环依赖，故障影响范围可预期 | 有效 | 03 |
| D3 | ~~前期用 Keycloak 作为 Auth 服务，每租户一个 realm~~ | | 已被 D16 替换 | 02 |
| D4 | ~~登录使用 Keycloak 内置用户；租户可配置自己的 OIDC IdP；微信放后期~~ | | 已被 D17 替换 | 02 |
| D5 | ~~token 由 Keycloak 直接签发，边界与服务只验签，不换发~~ | | 已被 D18 替换 | 02 |
| D6 | ~~`tenant_id` 在 realm 里固定，`iss` 与 `tenant_id` 绑定~~ | | 已被 D19 替换 | 02、04 |
| D7 | `roles` 不进 token，由 Tenant Core 按 `tenant_user_id` 查询（同一接口返回租户状态与已开通模块） | 角色变更立即生效；Auth 侧不需要同步角色 | 有效 | 02 |
| D8 | 下游不依赖外部身份的 ID，只用 `tenant_user_id` | 更换登录方式或 broker 时不需要重新对应 | 有效 | 02 |
| D9 | ~~租户管理员经 Auth Admin 门面管理 IdP、client、用户~~ | | 已并入 UAM 的 Admin API（D16） | 02 |
| D10 | ~~用户预置，不做 JIT~~ | | 已被 D20 替换 | 02 |
| D11 | ~~会话吊销用 OIDC Back-Channel Logout~~ | | 已被 D18 替换：删除不透明令牌即时生效 | 02 |
| D12 | ~~外部服务用 client_credentials，登记在租户 realm~~ | | 已被 D24 替换 | 02 |
| D13 | 每个身份域一个 `aud`（`tenant-services`、`platform-services`） | 简化；现在指内部 JWT 的 `aud` | 有效（对象改为内部 JWT） | 02 |
| D14 | 服务间只有“透传”和“service token”两种调用方式 | 前期服务少，不做 on-behalf-of | 有效 | 03 |
| D15 | 事件采用 Postgres Outbox 加轻量消息队列，至少一次投递，消费者幂等 | 前期不上 Kafka | 有效 | 05 |
| D16 | **UAM 取代 Keycloak 作为 Auth 服务**：独立自研服务，同一份代码，每个 cell 一套部署（D28）。平台 UAM 只服务 platform_staff，租户 UAM 多租户，用 `tenant_id` 做逻辑隔离（表带 `tenant_id`，Postgres 行级安全，应用用受限数据库角色）。各部署有独立的库、`iss` 与签名密钥。不使用 `tenant_id = 0` 方案。UAM 同时承载原 Auth Admin 门面的管理职责。平台侧即“平台租户”的 cell，见 D26 | 登录方式、令牌格式、claims 与密钥完全自控；平台与租户的数据和密钥天然隔离，保持 D1 | 有效 | 02 |
| D17 | **登录方式可扩展**：UAM 定义 `Authenticator` 接口，输出统一的“已验证身份”（类型、提供方、主体、已验证属性）。一个用户可关联多条 identity（password、oidc、wechat 等）。第一阶段只实现用户名密码；外部 IdP、broker（如 Keycloak）、微信为后期，作为新的 Authenticator 接入，不改令牌签发与下游。架构图须画出这些扩展点（后期用虚线） | 避免登录方式变化影响其余部分 | 有效 | 02 |
| D18 | **终端凭证是不透明令牌**：UAM 签发，Web（BFF 的 session 里存放）、桌面端使用；网关 ext_authz 校验后换成内部 JWT 转发，内部 JWT 不出集群，各服务仍本地验签。删除令牌即时吊销，租户暂停、用户禁用、强制下线也在 UAM 签发和刷新时拦截 | 吊销即时生效；对外凭证不含 claims；broker 与登录方式可替换 | 有效 | 02、03 |
| D19 | `tenant_id` 由 UAM 的认证配置决定，即用哪个租户的认证配置完成校验，用户就属于哪个租户，绝不取自外部声明。identity 绑定键为 (租户, 类型, 提供方, 主体)，不用全局 `sub`，不按邮箱自动合并 | 防止跨租户接管 | 有效 | 02、04 |
| D20 | **用户由 UAM 创建**：UAM 生成 `tenant_user_id`，发“用户已创建”事件，Tenant Core 以该 ID 幂等异步建档案（新用户无角色）。第一阶段用户来自 CLI 冷启动与管理员创建（不做 JIT）；接入外部 IdP 后，按租户与 IdP 的开关允许登录时自动创建（JIT），并遵守：租户来自认证配置；绑定键见 D19；默认无角色；创建幂等；租户暂停或用户禁用时拒绝签发；邮箱关联只对已验证域名的 IdP 开放 | UAM 不依赖 Tenant Core 同步调用，符合分层规则 | 有效 | 02、05 |
| D21 | **登录方式的关联规则**：新增登录方式须在已登录状态下进行，且新方式自己通过验证；解绑至少保留一种可用登录方式；一个外部身份只能绑定一个用户 | 防止账号被抢占或锁死 | 有效 | 02 |
| D22 | **UAM 自己存密码**：argon2id 哈希，密码策略，连续失败锁定，登录端点限流（“前期不做限流”的唯一例外）；找回密码与通知经 Notification；凭证不进日志、不出 UAM | 第一阶段没有 broker | 有效 | 02 |
| D23 | **CLI 只做冷启动**：用于初始化平台 UAM 的第一个 staff 与租户 UAM 的初始租户及第一个租户管理员。初始密码不作为命令行参数（交互输入、标准输入或一次性链接），首次登录强制改密；幂等；只能在受控环境运行（如 Kubernetes Job），执行入审计。日常开通租户走 Platform Service 事件，租户注册表以 Platform Service 为准，CLI 创建的记录需与注册表对账。**平台租户及其模块授权（Platform Service、数据分析等）同样由 CLI 冷启动写入**，之后才由 Platform Service 管理 | 避免两处数据不一致 | 有效 | 02、04 |
| D24 | **外部服务 M2M 后期再做**，随 broker 接入：外部服务先在 broker 登录，UAM 验 broker 的 token，再换不透明令牌；登记的权限范围以 UAM 为准，不取 broker 的声明。第一阶段不支持外部服务接入 | 借用 broker 的 client_credentials 能力，不自建 | 有效 | 02 |
| D25 | **staff 请求也经网关的换发路径**：Platform BFF 经网关内部 listener，BFF 不接触 JWT | 所有流量同一条换发路径 | 有效 | 02、03 |
| D26 | **平台是特殊的 silo 租户**：平台租户部署在独立 cell，有自己的 UAM（即平台 UAM）、Tenant Core、数据库与审计。Platform Service 与未来的平台级数据分析服务是该租户已开通的模块，走同一套模块授权与 RBAC。platform_staff 即平台租户的 tenant_user，档案与角色在平台 cell 的 Tenant Core。Platform BFF、Audit 与租户侧是同一份代码的不同部署。平台租户是 UAM 的保留租户，任何租户管理接口都不能创建、修改或读取它。租户 cell 的服务只信租户 UAM 的 `iss`，平台 cell 的服务只信平台 UAM 的 `iss`，信任根不同，因此 `domain` 声明可去掉（`aud` 是否仍分开待定）。平台 cell 不可用时，平台侧无法管理租户，但不影响各租户 cell 使用 | 复用 cell、模块授权与 RBAC，减少平台与租户两套特例；保持 D1 的分库与密钥隔离 | 有效 | 01、02、03 |
| D27 | **服务分为平台级与业务级**，二者都在业务服务层，但不是同一类：**平台级服务**（Platform Service、未来的数据分析等）是平台租户的模块，只部署在平台 cell，只信平台 UAM 的 `iss`，服务平台 staff，持有租户注册表与模块授权等平台数据；**业务级服务**是租户的业务模块，部署在租户 cell（pool 或 silo），只信租户 UAM 的 `iss`，按 `tenant_id` 隔离数据。两类服务之间不做同步调用（跨 cell 只用事件），业务级服务不依赖平台级服务：租户注册与授权经事件投影到本 cell 的 Tenant Core，由 Tenant Core 提供查询。Tenant Core、Audit、Notification、UAM 两类 cell 各有一套部署，不属于这两类 | 平台数据与租户数据的故障域、信任根与权限边界清晰 | 有效 | 01、03 |
| D28 | **每个 cell 有自己的网关**：平台 cell 与每个租户 cell（pool 或 silo）各部署自己的 Envoy Gateway 与 ext_authz，不共用网关。ext_authz 只读本 cell 的 UAM 令牌数据，只用本 cell 的签名密钥换发内部 JWT。每个 cell 自包含：网关、UAM、BFF、Tenant Core、Audit 各一套（**每个 cell 一套 UAM，不跨 cell 共用；一个 cell 可被多个租户使用（pool cell，其 UAM 多租户、按 `tenant_id` 逻辑隔离），silo cell 只服务一个租户，因此天然独占 UAM**）。进入 cell 之前的入口路由（租户如何到达所属 cell 的网关）在 cell 之外解决，倾向每租户一个域名或子域名，由 DNS 指向所属 cell 的入口，具体待定。因此网关不再按 `cell_id` 路由，`x-cell-id` 头取消 | cell 自包含，故障与信任根隔离；网关不需要跨 cell 的租户路由表 | 有效 | 01、02、04 |
| D29 | **UAM 与 Tenant Core 前期合并部署**：一个服务、一个进程、一个数据库，内部按模块保留边界（认证模块：登录、身份、凭证、令牌；租户核心模块：档案、RBAC、租户配置、权限查询）。模块间用进程内调用与 Spring events，D20 的“用户已创建”等事件在同一事务内完成，不经 Event Bus；对外接口（登录、令牌、权限查询）与对外事件的契约不变。边界规则：凭证与令牌表同档案表分 schema、分数据库角色，两个模块不直接读写对方的表；ext_authz 通过认证模块的接口或令牌缓存读令牌，不读档案表。需要拆分时（负载、故障域或安全要求）只替换传输，契约不变。仍是每个 cell 一套 | 前期租户少，少一个服务和一条跨服务事件链路；边界保留以便拆分 | 有效 | 01、02、05 |
| D30 | **UAM 是 Tenant Core 的认证模块，属于核心域层，不再是侧栏**：Tenant Core 由认证模块（UAM：登录、identity、凭证、令牌、JWKS）与租户核心模块（档案、RBAC、租户配置、权限查询）组成，每个 cell 一套。网关、BFF 对 UAM 的访问是向下的同步调用，遵守分层规则，不再有“对侧栏的访问”这一例外。D29 的部署合并与边界规则不变。图与文档按 cell 分层描述：入口层、业务服务层、核心域层（Tenant Core）、基础服务层 | 认证与档案同属租户 cell 的核心域，分层规则统一 | 有效 | 01、03 |

## 2. 前期省掉的功能与触发条件

| 省掉的 | 触发条件 | 做法 |
|---|---|---|
| 通用限流 | 出现滥用、需要保护后端或按套餐限额 | ext_authz 已在路径上，可在其中加按租户、按令牌的限流；先用 Envoy Gateway 自带的限流（按路由、IP）。UAM 登录端点的失败锁定不属于此项，第一阶段就做（D22） |
| Service Mesh | 服务数量增多，需要证明直接调用方身份 | 加 mTLS 与 SPIFFE 授权策略；之前可用按调用方收紧 NetworkPolicy 或 ServiceAccount 令牌过渡（见 03） |
| 外部 IdP 与 broker（含 Keycloak）、JIT | 租户要求用自己的 IdP、或需要联合登录 | 新增 Authenticator 实现；JIT 按 D20 的约束启用 |
| 外部服务 M2M | 外部服务要接入 | 见 D24，随 broker 接入 |
| API Key | 客户要求静态 key | 兼容层，由 UAM 登记 key 后换发不透明令牌 |
| on-behalf-of | 出现需要缩小权限代用户调用的场景 | UAM 增加令牌交换接口 |
| 微信登录 | 业务需要 | 实现 `WeChatAuthenticator` |
| MFA | 安全要求提高 | UAM 增加二次验证，令牌里的 `amr` 记录登录方式 |
| SCIM | 租户 IdP 的用户同步与离职管理 | 后期评估 |
| 拆分 Platform Service | 边界稳定后 | 把 tenant registry、staff 管理、模块授权拆成独立服务，Spring events 换成外部事件总线 |

## 3. 待定项

### 3.1 认证

- [ ] Tenant Core 权限查询接口：返回内容（角色、租户状态、已开通模块）、缓存 TTL、权限变更的失效方式（事件通知或短 TTL），以及缓存失效后 Tenant Core 不可用时的降级行为。
- [ ] 不透明令牌的有效期、刷新与轮换（尤其桌面端）；内部 JWT 的有效期（建议 1 到 5 分钟）及按令牌缓存的方式。
- [ ] ext_authz 查令牌的方式（Redis 加本地短缓存，还是回源 UAM）、缓存时长（决定吊销延迟）、高可用与失败即拒绝。
- [ ] ext_authz 签发内部 JWT 的密钥存放（进程内、由 KMS 加密）与轮换；各 cell 的密钥分开。
- [ ] 管理员创建用户的入口（经 Tenant BFF 调 UAM Admin API）与初始密码交付方式。
- [ ] 密码策略、失败锁定阈值、找回密码流程。
- [ ] 桌面端的登录流程（UAM 自带登录页，系统浏览器加回调）与令牌存放。
- [ ] 租户 UAM 的行级安全方案与数据库角色划分。
- [ ] 系统发起调用（定时任务、事件消费者）的 service token 如何签发：由 UAM 为服务账号签发，还是用 Kubernetes ServiceAccount 令牌；第一阶段没有 M2M，需单独确定。
- [ ] 令牌是否记录登录方式（`amr`），以及 `domain` 去掉后 `aud` 是否仍按平台、租户分开。
- [ ] 后期接入 broker 时，M2M 的验证与换发流程、broker 的选型。

### 3.2 多租户与开通

- [ ] 进入 cell 之前的入口路由：倾向每租户一个域名或子域名，由 DNS 指向所属 cell 的网关；桌面端如何发现租户的入口（输入租户域名或租户标识）。
- [ ] 开通失败的补偿与重试方式。
- [ ] 日常开通租户时首个租户管理员的创建方式（CLI 只覆盖冷启动）：倾向由开通事件带上管理员的登录名与邮箱，UAM 创建用户并经 Notification 发送一次性设置密码链接。
- [ ] CLI 冷启动后，租户 UAM 里的租户记录与租户注册表的对账方式。
- [ ] 暂停租户时吊销已有令牌的方式（批量删除，还是租户级版本号）。
- [ ] 平台租户冷启动写入的内容（保留租户记录、模块授权、第一个 staff）与 Platform Service 接管后的对账方式。

### 3.3 事件

- [ ] 消息队列选型。
- [ ] 事件信封的字段命名与序列化格式。
- [ ] 重试、死信与告警策略。
- [ ] 开通流程里的完成类事件、“用户已创建”事件的命名。
- [ ] 平台级数据分析服务：数据来源与边界（须守住“租户内部数据对平台侧不可见”）、脱敏指标事件的契约。等实现该服务时再定，当前不设计。

### 3.4 服务间调用

- [ ] 哪些内部接口需要限定调用方，以及采用按调用方收紧 NetworkPolicy、ServiceAccount 令牌中的哪种做法。

## 4. 历史记录：Keycloak 与自研的对比（已被 D16 替换）

> 以下对比基于原方案（Keycloak 对 SAS）。最终采用自研 UAM，且第一阶段范围更小：只做用户名密码，不含 broker 与 M2M，故第 4.2 节的开发量不再适用，仅保留作决策背景。

对应决策 D3。“自研”指 Spring Authorization Server（SAS）加自研 broker，即 02 第 11 节的切换预案。侧重点：功能覆盖与开发量。人天为粗略估算，落地前以原型验证。

### 4.1 按本设计所需能力对比

| 能力（来源） | Keycloak | 自研（SAS + 自研 broker） | 自研开发量（估） |
|---|---|---|---|
| 授权码 + PKCE、refresh 轮换、discovery、JWKS | 内置 | SAS 内置 | 配置为主，约 3 人天 |
| client_credentials 与 M2M client 管理（D12） | 内置，client 加 service account，secret 轮换可配 | SAS 支持协议；client 管理 API、secret 只显示一次、轮换、吊销需自研 | 约 8 人天 |
| 内置用户登录（邮箱密码） | 内置，含找回密码、邮箱验证、暴力破解检测 | 需自建用户表、密码哈希、找回与验证流程、防暴力破解 | 约 15 到 20 人天 |
| 租户自带 OIDC IdP，每租户多个（D4） | realm 原生 Identity Provider | Spring Security OAuth2 Client 加 `idp_connection` 表，动态 `ClientRegistrationRepository`；SSRF 校验在两种方案里都由门面做 | 约 12 人天 |
| 租户隔离与 `iss`/`tenant_id` 绑定（D6） | 每租户一个 realm，`tenant_id` 在 realm 配置里固定 | 单实例多 issuer 或多租户路由，需自行保证 `tenant_id` 来自服务端 | 约 8 人天 |
| 多租户登录页（品牌、列出 IdP、邮箱域名发现） | 主题机制加配置 | 全部自研 | 约 10 人天 |
| claims 契约（`tenant_user_id`、`cell_id`、`client_type`） | protocol mapper 配置 | `OAuth2TokenCustomizer`，直接读投影 | 约 3 人天 |
| 会话与 Back-Channel Logout（D11） | 内置 | 需自研会话管理与通知 | 约 8 人天 |
| 用户预置同步（D10） | 门面经 Admin API 调用 | 门面直接写自有库（更简单） | 两边接近 |
| 开通租户（建 realm、client、mapper） | 门面调 Admin API，脚本化 | 写数据库记录即可 | 自研更省 |
| 微信登录（后期） | 社区扩展或自写 SPI，要验证可用性 | 实现 `IdpAdapter` 接口 | 两边都需开发，约 5 到 8 人天 |
| KMS 签名 | 需额外扩展，前期不做 | 自定义 `JWKSource` / `JwtEncoder` | 约 5 人天 |
| 管理员 Admin API 与审计 | 门面承担，Keycloak 提供 Admin API 与事件 | 门面加自建存储 | 两边接近 |

### 4.2 汇总

| 项 | Keycloak | 自研 |
|---|---|---|
| 前期需要开发的部分 | Auth Admin 门面（IdP 管理、M2M client、用户同步、开通脚本），约 20 到 25 人天 | 门面 + 用户体系 + 登录页 + 会话 + broker + client 管理，约 70 到 90 人天 |
| 持续维护 | 跟随 Keycloak 升级，维护主题与 SPI；realm 增多后的运维成本（见下） | 自己维护安全相关代码与漏洞响应，包括密码、会话、OIDC 一致性 |
| 协议与安全面 | 久经验证，漏洞由社区修复 | 协议实现靠 SAS，其余都是自研代码，安全评审工作量大 |
| 对下游的影响 | 无（契约相同） | 无（契约相同） |

### 4.3 结论

前期租户不超过 5 个，选 Keycloak：把用户登录、IdP 对接、会话、client_credentials 这些通用能力交给成熟产品，自研只做门面，开发量约为自研的四分之一到三分之一，且能更早上线。

Keycloak 的主要代价是 realm 数量增长后的运维成本（内存、启动时间、Admin API 速度），以及定制 claims、微信接入、KMS 签名需要写 SPI 或扩展，这些在前期规模下可以接受。

自研的优势是 claims 与多租户模型完全贴合、不受 realm 模型约束、可直接接 KMS，但要自己承担用户体系与安全面。因此不在前期做，守住 02 第 11 节的四条边界，保留切换余地。

### 4.4 重新评估的触发条件

与第 2 节“切换到 SAS”一致，出现以下任一情况时，按本节的对照重新估算：

- 租户数量增长到几百个以上，realm 管理或性能明显吃力；
- 有合规要求必须用 KMS 签名，或 claims 定制超出 mapper 能力；
- 微信等适配在 Keycloak 上难以维护；
- 单个租户要求独占实例的数量增多，运维成本过高。
