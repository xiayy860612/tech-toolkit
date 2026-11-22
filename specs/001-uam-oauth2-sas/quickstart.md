# Quickstart: 验证 auth 端到端

用来验证 spec 的主流程是否跑通。命令基于 plan 里约定的 `auth/` 单模块项目，**代码尚未创建**，脚手架（tasks 的第一阶段）完成后才能执行；若实际命令有出入，以 `auth/AGENTS.md` 为准并回改本文。

## 前置条件

- Java 21、Maven、Docker（Testcontainers 与本地 PostgreSQL 都用）。
- 环境变量见 `auth/.env.example`：数据库连接（owner 用于迁移、app 用于运行）、`AUTH_ISSUER`、签名密钥（`dev` profile 可省略）、`AUTH_WEB_RETURN_TO_ALLOWLIST`、各 client 的 secret。

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

首次部署创建第一个管理员（交互输入密码，不接受命令行参数）：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=bootstrap
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

接口细节见 [contracts/](contracts/)，表结构见 [data-model.md](data-model.md)。
