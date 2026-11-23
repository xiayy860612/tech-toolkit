# Quickstart: 验证 auth 端到端

用来验证 spec 的主流程是否跑通。命令已于 2026-11-23 在本机实跑核对；若与实际有出入，以 `auth/AGENTS.md` 为准并回改本文。

## 前置条件

- Java 21、Maven、Docker（Testcontainers 与本地 PostgreSQL 都用）。
- 环境变量见 `auth/.env.example`：数据库连接（owner 用于迁移、app 用于运行）、`AUTH_ISSUER`、`AUTH_SIGNING_KEYS`、`AUTH_ADMIN_REDIRECT_URIS`，以及 dev profile 的示例用户与 client1、client2 的 secret（`AUTH_DEV_*`，没有默认值，必须设置）。
- 本机 5432 若已被占用，`docker-compose.yml` 的端口映射要改，并相应设置 `AUTH_DB_URL`。
- 生成 ES256 签名密钥（PKCS#8 PEM）：

```bash
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out current.pem
```

## 一、自动化验证（门禁）

```bash
cd auth
mvn verify          # 单元 + 集成 + 契约测试，JaCoCo 行覆盖率 ≥ 90%，Spotless 格式检查
```

期望：全部通过；覆盖率门禁不低于 90%。

## 二、本地起服务

```bash
cd auth
docker compose up -d postgres                          # 本地数据库
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`dev` profile 才允许省略 `AUTH_SIGNING_KEYS`（用临时密钥）；服务默认监听 9000，与 `AUTH_ISSUER` 的端口一致（`AUTH_PORT` 可改）。

首次部署创建第一个管理员和公共 client `auth-admin`（交互输入密码，或从标准输入管道传入，不接受命令行参数；需要与服务相同的环境变量，包括 `AUTH_SIGNING_KEYS`）：

```bash
printf '%s\n' 'Admin-Passw0rd-12345!' | AUTH_BOOTSTRAP_ADMIN_USERNAME=root \
  mvn spring-boot:run -Dspring-boot.run.profiles=bootstrap
```

管理员第一次登录必须先在 `/account/password` 改密，之后才能拿到令牌。管理 API 的令牌通过 `auth-admin` 取得：授权码 + PKCE，scope 为 `openid auth.admin`，回调是 `AUTH_ADMIN_REDIRECT_URIS` 里登记的回环地址。

性能测试（SC-004）默认不跑，且必须关闭 JaCoCo：

```bash
mvn test -Djacoco.skip=true -Dtest.excluded.groups=none -Dtest=LoginLoadIT
```

## 三、场景验证（对应 spec）

| # | 场景 | 步骤 | 期望 |
|---|---|---|---|
| 1 | US1 登录 | 用示例 client1 发起 `/oauth2/authorize` → 登录页输入用户名密码 → 用授权码换令牌 | 回到 client1 的回调地址并带回授权码；令牌只通过令牌端点返回给 client，不出现在浏览器重定向里 |
| 2 | US1 失败 | 输入错误密码；再输入不存在的用户名 | 两次提示完全相同 |
| 3 | US1 回调校验 | 用登记之外的 `redirect_uri` 发起 `/oauth2/authorize` | 显示错误页，不发生重定向 |
| 4 | US2 单点登录 | 用 client1 登录后，在同一浏览器会话访问示例 client2 的授权地址 | 免输入直接得到授权码 |
| 5 | US2 单点退出 | 在 client1 触发退出 | 10 秒内 client2 的 back-channel 端点收到通知，再访问需要重新登录 |
| 6 | US3 刷新 | 令牌过期后用刷新令牌换新 | 成功；再用旧刷新令牌 → `invalid_grant`，且新令牌也失效 |
| 7 | US3 吊销 | 管理 API 禁用用户后刷新 | 被拒绝 |
| 8 | US4 锁定 | 同一账号连续输错 5 次，再输正确密码 | 15 分钟内被拒绝，提示与密码错误逐字相同 |
| 9 | US5 client | 管理 API 登记 client4 并走一遍登录；再停用 client2 | client4 可用，client2 被拒绝，client1、client3 不受影响 |
| 10 | US6 验证 | 取 `/oauth2/jwks` 离线验证访问令牌；轮换密钥后再验证旧令牌 | 旧令牌在有效期内仍通过，新令牌用新密钥签发 |
| 11 | FR-019a 权限 | 用普通用户令牌、无令牌分别调用 `/admin/v1/users` | 分别 403、401 |

## 四、人工验收

以下两项是人的操作，无法自动化，跑完全部场景后单独记录结果：

| 成功标准 | 做法 | 通过条件 |
|---|---|---|
| SC-001 | 找 5 名没接触过系统的测试者，让他们从受保护应用开始完成首次登录并回到原页面，记录每人用时 | 至少 95% 在 90 秒内完成且无需帮助（5 人中最多 0 人超时） |
| SC-006 | 让运维只照 `contracts/app-integration.md` 登记一个新 client 并联调通过，记录总用时 | 总用时不超过 30 分钟，且不需要改动 auth 的程序 |

## 五、2026-11-23 实跑结果

在本机（macOS，12 个逻辑核，PostgreSQL 18 容器）用 curl 逐条走完场景 1–11，全部符合期望。实跑中发现并修复三处问题（见 research D18、D20）：

- `bootstrap` profile 把 web 类型设为 `none`，缺少 `HttpSecurity` 而起不来 → 改为随机端口、关闭后台任务、执行完退出。
- `dev` profile 没有种子配置，也没有与 issuer 对应的端口 → 新增 `application-dev.yml`，默认端口 9000。
- 场景 10 前已修复的轮换缺陷（双密钥时无法签名）在真实重启下确认生效：发布 B → 切换 → 移除 A，旧令牌在 A 移除前一直可验证，移除后因 `kid` 不在 JWKS 而无法验证（符合预期）。

补充观察：场景 9 中停用 client2 后，它的授权请求在登录之前就返回 400 错误页（不是跳登录页）；没有待处理授权请求的登录成功后落在 `/`（404），见 research D21。

| 场景 | 结果 |
|---|---|
| 1–6、8 | 通过 |
| 7（禁用用户后刷新 → `invalid_grant`） | 通过 |
| 9（client4 可用，client2 被拒，client1 不受影响） | 通过 |
| 10（离线验证，轮换三步） | 通过 |
| 11（无令牌 401、普通用户 403、管理员 200） | 通过 |

### 人工验收（待人工完成）

SC-001（5 名测试者首次登录）与 SC-006（运维只凭 `contracts/app-integration.md` 接入新 client）需要真人参与，尚未执行，结果记在这里：

| 成功标准 | 日期 | 参与者 | 结果 |
|---|---|---|---|
| SC-001 | | | 待执行 |
| SC-006 | | | 待执行 |

接口细节见 [contracts/](contracts/)，表结构见 [data-model.md](data-model.md)。
