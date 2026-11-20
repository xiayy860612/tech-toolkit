# 验证记录：Tenant Admin Center

**对应任务**：T119（按 `quickstart.md` 验证场景 1–19）、T120（安全复核）
**验证日期**：2026-11-20
**被验证的代码**：分支 `chore/from-f91e035a`（含合并后的后端与前端/BFF）

## 1. 自动化门禁（合并后的代码）

| 项 | 结果 |
|---|---|
| 后端 `mvn verify`（Spotless、测试、JaCoCo 门禁） | 通过：**148** 个测试，行覆盖率 **94.1%**（门槛 90%）（变更「用户名密码内置于 User、整型自增 ID」后复跑） |
| 前端 `pnpm lint` / `pnpm typecheck` / `pnpm build` | 全部通过 |
| 前端 `pnpm test:coverage` | 通过：27 个测试文件、**264** 个测试，行覆盖率 93.8% |
| Playwright E2E（对真实后端 + PostgreSQL + Redis + 已构建的前端） | **14 / 14 通过** |
| 性能测试 `ListPerformanceIT`（SC-005：1,000 用户、100 并发会话，列表与搜索） | p95 = **521 ms**（500 次请求，目标 ≤ 2 s） |

E2E 环境按 `frontend/AGENTS.md`「本地联调」搭建：Docker Compose（PostgreSQL 16、Redis 7）+ 后端 `dev` profile + `pnpm build && pnpm start`。

## 2. quickstart 场景 1–19

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 默认租户（无租户入口） | ✅ | E2E `login-journey`「login page has no tenant input and none of the removed entries」 |
| 2 | 首次登录强制改密 | ✅ | E2E `login-journey`「login → forced password change → user list」；`AuthApiContractIT` |
| 3 | 登录失败不泄露 | ✅ | `AuthApiContractIT.everyKindOfLoginFailureReturnsTheSameBody`、`LoginServiceTest`；E2E「wrong credentials show the unified message」 |
| 4 | 锁定与解锁 | ✅ | `LoginServiceTest.locksTheUserAfterFiveFailuresForFifteenMinutes`；解锁见 `UserServiceTest.unlockClearsFailedAttemptsAndTheLock`、`UsersApiContractIT`；E2E `credentials-journey`（连续输错 5 次 → 锁定 → 管理员解锁 → 可登录） |
| 5 | 创建用户（系统生成临时密码） | ✅ | E2E `users-journey`「create a user … forced to change it」 |
| 6 | 用户名冲突 | ✅ | E2E `users-journey`「a duplicate username is rejected inline」；另一租户同名由 `SameUsernameAcrossTenantsIT` 验证（租户 1 为测试夹具） |
| 7 | 用户名密码内置于 User | ✅ | 创建用户必带用户名与密码：`UserServiceTest`、`UsersApiContractIT`；`app_user` 的 `username`/`username_normalized`/`password_hash` 非空、无 `account`/`password_credential` 表：`SchemaConstraintsIT`；详情页「登录账号」区无 Account 列表、「新增 Account」、停用与删除：`CredentialSection.test.tsx`、E2E `credentials-journey`。其他登录方式 Account（手机号、微信、OAuth2.0）及"每类型至多一个"、多类型并存（SC-004）待第二种类型落地后验证；扩展点见 `LoginExtensionPointIT` |
| 8 | 主键与公开标识 | ✅ | `SchemaConstraintsIT`（user、审计 id 为 bigint 自增且递增，会话 id 为 uuid）；E2E `credentials-journey`（接口里用户 id 为整数、会话 id 为 UUID）；`UsersApiContractIT`/`AuthApiContractIT` 契约校验 |
| 9 | 重置密码 | ✅ | E2E `credentials-journey`；`UserServiceTest.resetPasswordWritesANewHashForcesChangeAndRevokesSessions`、`SessionRevocationIT` |
| 10 | 停用用户 | ✅ | `UserServiceTest`、`SessionRevocationIT`（会话立即撤销、重新启用后不复活） |
| 11 | 守卫（停用自己、改自己的角色、最后一名管理员） | ✅ | `UserServiceTest`（`CANNOT_DISABLE_SELF`、`CANNOT_CHANGE_OWN_ROLE` 升/降两个方向、`LAST_ADMIN`）、`UsersApiContractIT`、`ConcurrencyIT`；前端组件测试与 E2E `credentials-journey`「cannot lock themselves out」（界面隐藏/禁用 + 绕过界面直接调接口仍 409；仍可重置自己的密码） |
| 12 | **跨租户隔离（硬门禁）** | ✅ **100%** | `TenantIsolationIT`（含用户、解锁/重置密码、会话端点）、`SameUsernameAcrossTenantsIT`、`TenantContextIT`、`SchemaConstraintsIT` |
| 13 | 越权、降级与跨站写 | ✅ | E2E「a MEMBER is sent to /forbidden…」「a cross-site write to the BFF is rejected」；`JwtAuthenticationFilterIT`（降级后下一次请求即 403） |
| 14 | 会话超时与失效 | ✅ | `SessionServiceIT`（空闲 30 分钟滑动、绝对 8 小时）、`JwtAuthenticationFilterIT`；前端 `session-expiry.test.ts`；E2E `credentials-journey`（重置后原会话失效） |
| 15 | 并发编辑 | ✅ | `ConcurrencyIT`（`VERSION_CONFLICT`、最后管理员不被并发绕过） |
| 16 | 审计覆盖 | ✅ | `AuditLogsApiIT`、`AuditServiceIT` |
| 17 | 性能 | ✅ | p95 521 ms（见上） |
| 18 | **内部凭证校验（硬门禁）** | ✅ **全部通过** | 对运行中的后端实测见 §3；`InternalJwtVerifierTest`、`JwtAuthenticationFilterIT` |
| 19 | **会话管理（硬门禁）** | ✅ | E2E `sessions-journey`（强制下线单个/某用户全部；不能下线自己的当前会话）；`SessionsApiIT`、`SessionRevocationIT`；隔离见 `TenantIsolationIT` |
| — | **界面：侧边栏可收起、自适应（FR-031）** | ✅ | 组件测试 `AppShell.test.tsx`（收起/展开、`localStorage` 持久化与读写失败兜底、抽屉开合）、`responsive.test.tsx`（响应式关键类名）；E2E `layout-journey`（桌面收起后刷新保持；375px 宽度下抽屉可开合，`/users`、`/users/new`、`/sessions`、用户详情均无整页横向滚动） |

## 3. 场景 18 的实测探测（直连运行中的后端 `localhost:8080`）

| 请求 | 结果 |
|---|---|
| `GET /users` 无 `Authorization` | 401 |
| 垃圾令牌 | 401 |
| 过期的 user 令牌（真密钥签发） | 401 |
| 错误密钥签发的 user 令牌 | 401 |
| `alg=none` 令牌 | 401 |
| 签名合法但 `sid` 不存在的 user 令牌 | 401 |
| 合法的 `typ=service` 令牌访问用户端点 | 401 |
| 浏览器的会话 Cookie 直连后端 `/auth/me` | 401 |
| 经 BFF 携带伪造的 `X-Tenant-Id`、`?tenantId=99`、`Authorization: Bearer forged` | 与不携带时结果一致（`total` 27 对 27），伪造值未进入 JWT 与转发请求 |

BFF 登录响应体中没有会话令牌或 JWT，只通过 `HttpOnly` Cookie 下发。

## 4. 安全复核（T120）

| 检查项 | 结论 | 证据 |
|---|---|---|
| 会话 Cookie 属性 | ✅ | 实测 `Set-Cookie: SESSION_ID=<opaque>; Path=/; Max-Age=28799; HttpOnly; SameSite=Lax`；`Secure` 在代码中**默认开启**（仅 `COOKIE_SECURE=false` 时关闭，本地 http 联调用） |
| BFF 来源校验（跨站写） | ✅ | E2E「cross-site write … rejected」；`proxy-route.test.ts` |
| 限流 | ✅ | `rate-limit.test.ts`（Redis 固定窗口，超限 429 + `Retry-After`；Redis 不可用时放行并只记录错误类别） |
| 枚举防护 | ✅ | 五类登录失败响应体完全一致；用户名不存在时仍做等价哈希耗时（`LoginServiceTest`） |
| JWT 校验 | ✅ | 仅 EdDSA（拒绝 `none` 与对称算法）、iss/aud/exp 与时钟偏差、`kid` 轮换（`InternalJwtVerifierTest`） |
| 会话撤销与失效 | ✅ | 撤销与审计同事务；用户停用、重置/修改密码、强制下线、登出都撤销相应会话（`SessionRevocationIT`） |
| 角色以数据库为准 | ✅ | JWT 不含 `role`；降级后下一次请求即 403（`JwtAuthenticationFilterIT`、`MeRoleFreshnessIT`） |
| 日志脱敏 | ✅ | `LogRedactionIT`、前端 `log-redaction.test.ts`；实测后端与前端日志中 JWT 与密码字面量各 0 次 |
| 审计不含明文密码 | ✅ | 审计库中匹配明文密码的记录数 0；`AuditServiceIT` 验证敏感键被替换为 `[REDACTED]` |
| 会话令牌只存哈希 | ✅ | `session.token_hash` 长度 32 字节（SHA-256）；`SessionServiceIT` |
| RLS 与数据库角色 | ✅ | 应用角色 `tenantcore_app` 非超级用户、无 `BYPASSRLS`；5 张表全部启用 RLS；对 `audit_log` 仅有 `INSERT, SELECT`，且触发器拒绝更新/删除 |
| 仓库中无密钥 | ✅ | 被跟踪文件中无 `.keys/`、`.env.local`、私钥或 JWK 私钥分量；`.keys/` 与 `.env.local` 被 `.gitignore` 忽略 |
| 后端仅内网可达 | ⚠️ 部署要求 | 后端自身对 `typ=service`/无令牌请求一律 401，但本地进程监听所有网卡；生产**必须**通过网络策略/内网地址使其不被公网访问（已写入 `docs/system-design/tenant-admin-center.md`） |

### 发现与建议（均不阻塞本特性）

1. **本地开发库的属主角色 `tenantcore` 是超级用户**（Docker Compose 默认）。这只影响本地；生产迁移账号应使用非超级用户的表属主，应用账号保持 `tenantcore_app`。
2. **Argon2id 默认参数**（48 MiB、4 次迭代、并行度 1）在开发机上约 100–150 ms，换机型后需用 `PasswordHasherBenchmarkTest` 重新校准（`mvn test -DexcludedTestGroups=none -Dgroups=performance`）。
3. **进程内会话解析缓存**（默认 5 秒）只作用于 BFF 的 resolve 层，后端对每个 API 请求仍按 `sid` 校验会话，因此撤销对 API 调用立即生效；管理员对同一会话的页面级感知最多滞后一个缓存周期。
4. **租户固定为 0**：多租户隔离能力已实现并由测试夹具（租户 1）验证，但真实的第二个租户要等租户管理特性。
