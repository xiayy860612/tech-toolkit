# Quickstart：启动与逐场景验证

**Feature**: [spec.md](../../achieve-specs/admin-center/spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-23

本文是**验证指南**：实现完成后，按它在真实栈上证明特性端到端可用。契约与数据结构见 [contracts/](../admin-center/) 与 [data-model.md](../uam/data-model.md)，这里不重复。

> **状态**：`apps/uam`、`apps/admin-center`、`devops/` 还没有实现（由 `/speckit-tasks` 拆分后落地），所以下面的命令是**目标形态**，实现后以各应用 `AGENTS.md` 为准。**现在就能做的只有 S18（评审原型）**。

## 前置条件

- JDK 21、Maven、Node.js LTS、Docker（含 Compose）。
- 已有 PostgreSQL 16 与 Redis 7（本地用 `devops/local` 的 Compose 启动）。
- **网络边界**：UAM 只对 Admin Center 实例开放（网络边界，与服务密钥一起构成两层保护）；本地 Compose 里 UAM 不映射到宿主机端口，只在内部网络上被 Admin Center 访问。
- 本机 `/etc/hosts` 或测试环境提供 HTTPS 入口（`__Host-` 前缀的 cookie 要求 HTTPS；开发环境可用 `SESSION_COOKIE_NAME=asid` 去掉前缀）。

## 启动（目标形态）

```bash
# 1. 依赖：PostgreSQL、Redis
docker compose -f devops/local/docker-compose.yml --env-file devops/local/.env up -d

# 1b. 生成 Admin Center 的服务密钥（密钥只输出一次）
java -jar apps/uam/target/uam.jar generate-service-key --client admin-center
#   → 标准输出：服务密钥，存入 Admin Center 的密钥存储（UAM_SERVICE_KEY）
#   → 标准错误：对应的 sha256 哈希与 UAM_CLIENTS 配置片段，写进 UAM 的配置

# 2. UAM：首次部署的一次性初始化（不是运维入口）。密码只经标准输入
printf '%s\n' "$INITIAL_PASSWORD" | java -jar apps/uam/target/uam.jar init-admin --account-name admin --user-name "系统管理员"

# 3. 启动 UAM 与 Admin Center
mvn -f apps/uam/pom.xml spring-boot:run
npm --prefix apps/admin-center run dev
```

Admin Center 的环境变量（示例占位，真实值只经部署环境注入，禁止入库）：

| 变量 | 说明 | 默认 |
|---|---|---|
| `UAM_BASE_URL` | UAM 地址 | 必填 |
| `UAM_SERVICE_KEY` | Admin Center 调用 UAM 的服务密钥（`generate-service-key` 生成，仅输出一次） | 必填，缺失则启动失败 |
| `UAM_TIMEOUT_MS` | 调用 UAM 的超时 | `1500` |
| `SESSION_COOKIE_NAME` | 会话 cookie 名 | `__Host-asid` |
| `TRUSTED_PROXY_HOPS` | 取客户端地址时信任的代理跳数 | 部署相关 |

UAM 的关键配置（`UAM_CLIENTS` 必填：客户端名称、至多两个密钥哈希、权限；其余均有默认值，可调）：会话空闲 30 分钟 / 绝对 12 小时、登录失败 5 次 / 15 分钟、密码最短 10 位且含字母与数字。

## 验证场景

准备：用 `add-account` 命令给一个用户加第二个账号（多账号用户只能这样产生）：

```bash
printf '%s\n' "$SECOND_PASSWORD" | java -jar apps/uam/target/uam.jar add-account --user-id <用户标识> --account-name second
```

| # | 场景 | 步骤 | 预期 | 对应 |
|---|---|---|---|---|
| S1 | 多账号登录识别为同一用户 | 用账号 A、账号 B 分别登录 | 都进入 Admin Center，顶栏显示同一用户名；cookie `HttpOnly; Secure; SameSite=Strict`；会话页各有一条对应账号的会话 | US1、FR-002、FR-003 |
| S2 | 登录失败 / 限流 / 停用 | 错密码、不存在的账号名、已停用用户的账号各试一次；同一来源连错 5 次 | 三者提示完全一致（账号名或密码错误）且耗时无差别；第 6 次（即使密码正确）被拒并提示稍后再试；其他来源可正常登录 | FR-005、FR-006 |
| S3 | 单会话（一对一） | 同一账号在浏览器 1、2 先后登录 | 浏览器 1 下一次请求被带到登录页；会话页只剩一条该账号会话 | FR-004 |
| S4 | 用户列表、搜索、详情 | 打开用户管理，搜索账号名片段（大小写任意），进入多账号用户详情；进入无账号用户详情 | 列表分页、总数正确；详情列出该用户全部账号（无遗漏、无重复）；无账号用户显示空态；不存在的用户标识显示「用户不存在」 | US2、FR-017 ~ FR-020 |
| S5 | 新建用户 | 新建用户并登录；用已存在账号名（大小写不同）新建；密码不合规；双击提交；并发抢同一账号名 | 成功后进详情，且新账号立即可登录；冲突提示「账号名已被使用」且不建数据；不合规给出字段级原因；双击只建一个；并发只有一个成功 | US4、FR-021 ~ FR-023 |
| S6 | 停用 / 启用 | 让用户 X 登录后在详情页停用；X 刷新；X 再登录；启用后再登录；尝试停用自己；并发停用最后两个正常用户 | 停用后 X 的会话立即结束、X 下一次请求被带到登录页、X 登录被拒、X 从在线列表消失；启用后可重新登录且旧会话不恢复；停用自己与最后一个正常用户被拒（界面入口禁用，服务端同样拒绝）；并发下始终至少留一个正常用户 | US5、FR-024、FR-025、SC-005 |
| S7 | 在线用户与会话 | 两个用户登录（其中一个用两个账号），打开会话管理并进入各用户 | 在线用户只含已登录者；多账号用户有两条会话分别标明账号；自己的会话标注「当前会话」；页面与接口里没有会话标识 | US3、FR-028 ~ FR-032 |
| S8 | 强制下线 | 在 X 的会话详情强制下线其账号 A 的会话；再试对自己的会话；对已结束的会话重试 | A 的会话立即失效且下一次请求被带到登录页，X 的账号 B 的会话不受影响；自己的会话被拒并提示改用退出登录；已结束的会话提示「该会话已结束」不报错；账号与用户状态不变；X 之后可再次登录 | US6、FR-030、SC-006 |
| S9 | 修改密码 | 修改自己的密码：当前密码错、新密码弱、与当前相同、两次不一致、成功；连续输错当前密码 | 前四种给出字段级原因且不修改；成功后当前会话保持有效，退出后旧密码无法登录、新密码可登录；只改当前账号的密码，同用户的其他账号不受影响；连续输错被暂时拒绝 | US7、FR-026、FR-027 |
| S10 | 退出与到期 | 点退出；另将空闲时限临时调小后等待到期 | 退出后 cookie 被清、旧会话标识的请求被视为未登录；到期同样 | US8、FR-010、FR-011 |
| S11 | UAM 不可用 | 停掉 UAM，刷新已登录页面并尝试写操作；恢复 UAM | 读：显示「服务暂不可用」且**不清 cookie**；写：提示「操作未生效」，绝不显示成功；恢复后无需重新登录 | FR-009、FR-033 |
| S12 | 响应式 | 用 Playwright 视口矩阵 320 / 375 / 768 / 1024 / 1440 / 2560 走完登录 → 用户列表 → 新建 → 详情 → 停用 → 会话 → 强制下线 → 修改密码 → 退出；并仅用键盘走一遍 | 0 个页面整页横向滚动；手机表格变卡片、侧边栏变抽屉；对话框可滚动、确认与取消可达、焦点被限制并在关闭后回到触发元素；axe 无严重违规 | US9、FR-035、FR-036、SC-008 |
| S13 | 无会话粘滞与多实例一致 | 起 3 个 Admin Center + 3 个 UAM（`docker-compose.scale.yml`，nginx 轮询），登录一次后让请求分到各实例；经一个实例新建用户、强制下线 | 全部实例都识别为已登录；其他实例立即看到新用户；被强制下线的会话在任一实例的下一次请求都被视为未登录 | US10、FR-037 ~ FR-040、SC-010、SC-013 |
| S14 | 滚动重启 | 在持续访问下依次重启全部实例（`devops/local/scripts/rolling-restart.sh`） | 0 个用户被意外登出；重启结束后原会话仍有效 | FR-039、SC-012 |
| S15 | 规模与吞吐 | k6：10 万用户、1 万有效会话下压测列表与详情；实例数 1 → 4 对比吞吐 | 列表与详情 95% ≤ 2 秒；4 实例吞吐 ≥ 单实例 3.2 倍 | SC-009、SC-011 |
| S16 | UAM 直连：服务密钥、权限与操作人 | 直接调 UAM：不带密钥、错密钥、已撤销的旧密钥各调一个读接口和一个写接口；用没有断言权限的密钥调登录与写接口、再调只读接口；用有权限的密钥调写接口但不带操作人请求头；强制下线时把目标会话引用同时填作操作人会话引用；停用时把目标用户填作操作人用户；从非 Admin Center 的主机连接 UAM | 无 / 错 / 已撤销密钥 → 401 `client_unauthenticated`（响应不透露哪里不对）；无断言权限 → 登录与写接口 403 `client_forbidden`、只读接口可用；缺少操作人请求头的写请求 → 400 `operator_required`；目标等于操作人会话引用 → 409 `cannot_terminate_own_session`；目标等于操作人用户 → 409 `cannot_disable_self`；非 Admin Center 的主机连不上 UAM（网络边界） | FR-008、FR-042、FR-043、research R4、R5 |
| S17 | 安全与日志抽查 | 扫描页面、响应体、浏览器包、UAM 与 Admin Center 日志；从另一个站点伪造新建 / 停用 / 强制下线请求；检查写操作日志 | 没有明文密码（含新建与修改密码时的）、哈希、会话标识、服务密钥；伪造请求 100% 被拒；每次写操作（含被拒绝的）都有一条含操作人、对象、动作、时间、结果的日志，且不含敏感信息 | FR-031、FR-033、FR-034、SC-014 |
| S18 | **评审界面设计原型（现在可做）** | 打开 [prototype/index.html](../admin-center/prototype/index.html)，逐个场景与视口点一遍 | 覆盖 FR-041 列出的全部页面与状态，且没有范围外的入口；经你确认后才开始界面实现 | US11、FR-041、SC-015 |
| S19 | 服务密钥轮换与配置错误 | ① 给 `admin-center` 加第二个哈希并滚动发布 UAM，再让 Admin Center 换新密钥，最后删旧哈希；全程持续访问。② 把 Admin Center 的密钥故意配错，刷新已登录页面，再改回 | ① 全程 0 个用户被登出、0 个请求因密钥失败；删旧哈希后旧密钥 → 401；② 用户不被登出、cookie 不清除，页面提示服务暂不可用，UAM 与 Admin Center 日志有告警（不含密钥）；改回后无需重新登录 | US12、FR-044、SC-016 |

## 自动化对应

- S1 ~ S11、S16、S17：UAM 的集成 / 契约 / 并发测试与 Admin Center 的 Playwright E2E 覆盖；其中并发规则（S3、S5、S6）必须是确定性的并发测试。
- S12：Playwright 视口矩阵 + `@axe-core/playwright`。
- S13 ~ S15：`devops/` 下的多实例编排、滚动重启脚本与 k6 脚本；规模验收须在独占机器上多轮重复。
- 每个任务先写会失败的测试，再实现（根 AGENTS.md「测试先行」）；覆盖率口径与命令见各应用 `AGENTS.md`。
