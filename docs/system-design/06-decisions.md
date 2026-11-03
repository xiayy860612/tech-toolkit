# 06 决策记录与待定项

状态：设计基线（尚未落地）。记录关键决策、前期省掉的功能及其触发条件、仍待决定的事项。待定项定下来后，把结论写进对应的主题文档，并从这里删除。

## 1. 关键决策

| # | 决策 | 理由 | 详见 |
|---|---|---|---|
| D1 | 平台侧与租户侧分库、分审计，互不同步调用，只通过 Event Bus 交互 | 租户内部数据对平台侧不可见；故障与权限边界清晰 | 01、03、05 |
| D2 | 四层分层，同步调用只向下，同层与向上调用改用事件 | 避免循环依赖，故障影响范围可预期 | 03 |
| D3 | 前期用 Keycloak 作为 Auth 服务，每租户一个 realm，staff 单独一个 realm | 前期租户不超过 5 个，realm 隔离没有规模问题，且复用其登录、IdP 对接与 client_credentials 能力 | 02 |
| D4 | 登录使用 Keycloak 内置用户，不接 Supabase；租户可配置自己的 OIDC IdP（每租户多个）；微信放后期 | 减少用户存两处与适配器的开发；租户 IdP 是 realm 的原生能力 | 02 |
| D5 | 所有 token 由 Keycloak 直接按契约签发，边界与服务只做本地验签，不换发 | 去掉换发这一层；下游只依赖 JWKS 与 claims 契约 | 02 |
| D6 | `tenant_id` 在 realm 里固定，`iss` 与 `tenant_id` 天然绑定；可信 `iss` 清单来自租户注册表 | 防止一个租户的 realm 签发其他租户的 token | 02、04 |
| D7 | `roles` 不进 token，由 Tenant Core 按 `tenant_user_id` 查询（同一接口返回租户状态与已开通模块） | 角色变更立即生效；Keycloak 不需要同步角色，也便于将来迁移 | 02 |
| D8 | 下游不依赖 Keycloak 的用户 UUID，只用 `tenant_user_id` | 将来换 Auth 实现时不需要重新对应 `sub` | 02 |
| D9 | 租户管理员不直接访问 Keycloak，经 Auth Admin 门面管理 IdP、client、用户 | 安全规则（SSRF、防锁死）集中；将来换实现，管理界面不用改 | 02 |
| D10 | 用户预置：先在 Tenant Core 创建，再由事件同步到 Keycloak；不做 JIT | 前期租户少，省掉“首次登录”事件握手 | 02 |
| D11 | 会话吊销用 OIDC Back-Channel Logout，已签发 token 的吊销延迟不超过其有效期 | 使用标准机制，不自定义事件 | 02 |
| D12 | 外部服务用 OAuth2 client_credentials，不做 API Key；租户 M2M client 登记在租户 realm | 只剩一条认证路径，不需要 key 投影与事件同步 | 02 |
| D13 | 每个身份域一个 `aud`（`tenant-services`、`platform-services`），透传给下游不换发 | 简化，避免服务级 `aud` 带来的换发 | 02 |
| D14 | 服务间只有“透传”和“service token”两种调用方式 | 前期服务少，不做 on-behalf-of | 03 |
| D15 | 事件采用 Postgres Outbox 加轻量消息队列，至少一次投递，消费者幂等 | 前期不上 Kafka | 05 |

## 2. 前期省掉的功能与触发条件

| 省掉的 | 触发条件 | 做法 |
|---|---|---|
| 限流 | 出现滥用、需要保护后端或按套餐限额 | 先用 Envoy Gateway 自带的限流能力（按路由、IP），再扩展到按租户、按 `azp` |
| 自建 Edge Auth（ext_authz） | 需要按租户限流、租户暂停实时拦截、静态 API Key | 在 Gateway 前加 ext_authz 组件，不影响 token 契约 |
| Service Mesh | 服务数量增多，需要证明直接调用方身份 | 加 mTLS 与 SPIFFE 授权策略；之前可用按调用方收紧 NetworkPolicy 或 ServiceAccount 令牌过渡（见 03） |
| JIT | 租户自带 IdP 的用户太多，预置成本高 | Keycloak 的 first broker login 加自定义 authenticator，发“首次登录”事件，由 Tenant Core 创建用户 |
| API Key | 客户要求静态 key | 兼容层，由单独组件查 key 后换发 token，届时建立 key 投影 |
| on-behalf-of | 出现需要缩小权限代用户调用的场景 | 评估 Keycloak 对 token exchange 的支持，或换 SAS 后扩展 |
| 微信登录 | 业务需要 | 社区的微信 IdP 扩展或自写 Keycloak SPI |
| SCIM | 租户 IdP 的用户同步与离职管理 | 后期评估 |
| 切换到 SAS | 租户数量增长到几百个以上、需要 KMS 签名或更细的 claims 定制、微信等适配难以维护、realm 运维成本过高 | 见 02 的第 11 节 |
| 拆分 Platform Service | 边界稳定后 | 把 tenant registry、staff 管理、模块授权拆成独立服务，Spring events 换成外部事件总线 |

## 3. 待定项

### 3.1 认证

- [ ] Tenant Core 权限查询接口：返回内容（角色、租户状态、已开通模块）、缓存 TTL、权限变更的失效方式（事件通知或短 TTL），以及缓存失效后 Tenant Core 不可用时的降级行为。
- [ ] token 有效期取 5 分钟还是 10 分钟，以及是否为桌面端与外部服务启用 DPoP 或 mTLS 绑定。
- [ ] Keycloak 的高可用与备份方案，以及它宕机时对登录与续期的影响。
- [ ] Keycloak 签名密钥是否前期就接 KMS。
- [ ] 微信接入方式（社区扩展还是自写 SPI）。
- [ ] Envoy Gateway 按多个 `iss` 配置 JWT 验证、Keycloak 对 service account 的 client_credentials 支持，落地前需各验证一遍。

### 3.2 多租户与开通

- [ ] Gateway 按 `cell_id` 路由的配置方式。
- [ ] 开通失败的补偿与重试方式。
- [ ] 可信 `iss` 清单如何由注册表生成并生效。
- [ ] 首个租户管理员的创建方式。
- [ ] 暂停租户时是否同时禁用该租户的 realm。

### 3.3 事件

- [ ] 消息队列选型。
- [ ] 事件信封的字段命名与序列化格式。
- [ ] 重试、死信与告警策略。
- [ ] 开通流程里的完成类事件命名。

### 3.4 服务间调用

- [ ] 哪些内部接口需要限定调用方，以及采用按调用方收紧 NetworkPolicy、ServiceAccount 令牌中的哪种做法。
