# Keycloak 生产部署 Guideline（腾讯云 VM + docker-compose）

目标：在腾讯云 CVM 上用 docker-compose 部署 Keycloak + PostgreSQL，Keycloak **自带 TLS 直接对公网**，域名 `oidc.s2u2m.com`。命令均在 `keycloak/` 目录下执行。
文中「已验证」指在本地用同一份 compose 验证过；「未验证」是建议，上线前需在目标 VM 上自行演练。

## 1. 架构

```
Internet ──443 (HTTPS)──> Keycloak 容器 :8443 (start --optimized, 自带 TLS) ──JDBC──> Postgres 容器 :5432（仅 compose 内网）
                              └─ 管理端口 :9000（/health、/metrics，明文 HTTP，仅容器网络内）
```

- 没有 nginx 或其他反向代理；Keycloak 只提供 HTTPS，不监听明文 HTTP（已验证：明文访问连接失败）。
- 只有 Keycloak 的 443 映射到宿主机；Postgres 5432、管理端口 9000 都不对宿主机发布（已验证）。
- `/health`、`/metrics` 在公网端口上返回 404（已验证）。
- 对外地址 `KC_HOSTNAME=https://oidc.s2u2m.com`，决定 issuer 与所有重定向 URL。

## 2. 上线前准备清单

| 项 | 要求 |
|---|---|
| VM | 腾讯云 CVM（Linux），建议 ≥ 2 核 4GB（Keycloak 是 JVM，内存过小会被 OOM kill），磁盘预留数据库与备份空间 |
| Docker | Docker ≥ 24、Compose v2 |
| 域名 | `oidc.s2u2m.com` 的 A 记录指向 VM 公网 IP；**对外地址一旦被客户端使用就不要再改**（会改变 issuer，令牌校验方都要跟着改） |
| 备案 | VM 在**中国大陆地域**时，域名对外提供 80/443 服务须完成 ICP 备案，否则会被拦截；香港及境外地域无此要求（请自行确认） |
| 证书 | `tls.crt`（**含完整证书链**）与 `tls.key`，域名需与 `oidc.s2u2m.com` 匹配 |
| 安全组 | 入站只放行 TCP 443（公网）与 22（限制来源 IP）；**不要放行 5432、9000、8443** |
| 密码 | 管理员与数据库密码用强随机值，不写进仓库 |

## 3. VM 初始化

```bash
# 安装 Docker（按腾讯云镜像源或官方文档）
# 获取代码到 VM（git clone 或 scp），进入 keycloak/ 目录
```

**镜像拉取（国内网络）**：`quay.io`（Keycloak）与 Docker Hub（Postgres）在国内可能慢或不通（未验证）。可选：
- 腾讯云内网的 Docker Hub 加速：`https://mirror.ccs.tencentyun.com`（写入 `/etc/docker/daemon.json` 的 `registry-mirrors`，重启 Docker）。
- `quay.io` 无加速：在能访问的机器上拉取后推到腾讯云 TCR（容器镜像服务），再在 `.env.prod` 设
  `KEYCLOAK_BASE_IMAGE=<你的TCR地址>/keycloak:26.4`（`POSTGRES_IMAGE` 同理），VM 上先 `docker login` TCR。
- 或用 `docker save` / `docker load` 离线导入镜像。

## 4. 证书

把证书放到 VM 上的固定目录，例如 `/etc/keycloak/certs/`，文件名必须是 `tls.crt`、`tls.key`：

- 腾讯云免费证书下载 **Nginx** 版本，得到 `*_bundle.crt`（含证书链）与 `*.key`，改名为 `tls.crt`、`tls.key`。
- **私钥权限**：容器里的 Keycloak 以 **uid 1000、gid 0** 运行，所以私钥的**属主**必须是 uid 1000（光设属组 1000 不行），同时不要让其他人可读：

```bash
sudo chown 1000:0 /etc/keycloak/certs/tls.key
sudo chmod 400 /etc/keycloak/certs/tls.key
sudo chmod 644 /etc/keycloak/certs/tls.crt
```
（已在腾讯云 Ubuntu 24.04 上验证：属组为 1000 时启动失败，日志报 `AccessDeniedException /opt/keycloak/conf/certs/tls.key`；改为属主 1000 后正常。本地 macOS Docker Desktop 下 600 也能读。）

## 5. 配置

```bash
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

| 变量 | 生产值 | 说明 |
|---|---|---|
| `KC_HOSTNAME` | `https://oidc.s2u2m.com` | 对外完整地址（含协议），443 端口省略 |
| `KEYCLOAK_HTTPS_PORT` | `443` | 宿主机端口 → 容器 8443 |
| `TLS_CERT_DIR` | `/etc/keycloak/certs` | 内含 `tls.crt`、`tls.key` |
| `KC_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` | 自定 | 临时初始管理员，见 §7 |
| `KC_DB_NAME` / `KC_DB_USERNAME` / `KC_DB_PASSWORD` | 自定 | 内置 Postgres 首次启动时按此创建库与账号；**不要用示例里的 `change-me-*`** |
| `KEYCLOAK_VERSION` | `26.4` | 镜像版本，**必须固定**，不要用浮动标签 |
| `KEYCLOAK_BASE_IMAGE` / `POSTGRES_IMAGE` | 可选 | 镜像源覆盖，见 §3 |

> 数据库账号与密码只在 Postgres 数据卷**首次初始化**时生效；之后改 `.env.prod` 里的 `KC_DB_PASSWORD` 会导致 Keycloak 连不上，需要同时在库里 `ALTER ROLE`。

## 6. 首次部署

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q      # 校验，缺必填变量会报错
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build   # 构建并启动
docker compose -f docker-compose.prod.yml --env-file .env.prod ps              # 等 postgres、keycloak 均 healthy
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f keycloak
```

日志出现 `Listening on: https://0.0.0.0:8443` 即启动成功（已验证）。首次含建表（约 89 张），约 1 分钟。

## 7. 上线后立即做的事

1. 打开 `https://oidc.s2u2m.com/admin`，用 `.env.prod` 里的临时管理员登录。
2. 在 master realm 创建**个人/永久管理员**并开启 MFA，再**删除临时 bootstrap 管理员**（初始管理员只在首次初始化库时创建，改环境变量不会生效）。
3. 不要用 master realm 承载业务用户；为业务新建独立 realm。
4. 业务 realm：关闭不需要的注册/登录方式，设置密码策略与暴力破解保护，client 的 redirect URI 精确到具体地址，不用通配 `*`。

## 8. 验证清单

```bash
curl -s https://oidc.s2u2m.com/realms/master/.well-known/openid-configuration   # issuer 应为 https://oidc.s2u2m.com/realms/master
curl -s -o /dev/null -w "%{http_code}\n" https://oidc.s2u2m.com/metrics          # 应为 404
curl -s -o /dev/null -w "%{http_code}\n" https://oidc.s2u2m.com/health/ready     # 应为 404
echo | openssl s_client -connect oidc.s2u2m.com:443 -servername oidc.s2u2m.com 2>/dev/null | openssl x509 -noout -dates -subject
```

- 从外网探测 5432、9000、8443，应全部不通（安全组 + 未发布端口）。
- 日志无 `ERROR`。

## 9. 日常运维

**证书续期**：替换 `tls.crt`/`tls.key`（注意权限，见 §4）后重启 Keycloak，Keycloak 在启动时读取证书（短暂中断）：

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod restart keycloak
```
腾讯云免费证书有效期短，请设日历提醒或脚本化续期（未验证）。

**升级 Keycloak**（单实例会有短暂停机）：
1. 阅读目标版本的 Release Notes / Upgrading Guide，留意破坏性变更。
2. **先备份数据库**（见下），建议同时做一次云盘快照。
3. 改 `.env.prod` 的 `KEYCLOAK_VERSION`（及 `KEYCLOAK_BASE_IMAGE`），执行 `up -d --build`。Keycloak 启动时会自动迁移库结构。
4. 按 §8 验证，并抽查登录、token 签发、关键 client。
5. 回滚：**迁移后不能只回退镜像**，需同时恢复升级前的数据库备份。

**备份**（数据库内含 realm、用户、密钥，是唯一需要备份的数据）：

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > keycloak-$(date +%F).dump
```
- 用 crontab 定时执行，备份文件异地保存（如腾讯云 COS，加密、限制访问），并**定期演练恢复**（未验证）。
- 云盘快照可作为补充，但不能替代逻辑备份。

**Postgres 大版本升级**：数据卷与 PG 主版本绑定，18 → 19 这类升级不能直接换镜像，需 dump 后在新卷中恢复。

**日志**：容器日志默认无限增长，请在 `/etc/docker/daemon.json` 配置 `json-file` 的 `max-size`/`max-file` 做轮转（未验证）。

**监控**：`/health/ready` 与 `/metrics` 在容器内 9000 端口，不对外。需要采集时让采集器加入 compose 网络 `keycloak-prod_backend` 访问 `keycloak:9000`，不要把 9000 发布到宿主机（未验证）。

## 10. 安全加固建议

- `.env.prod` 权限 600，不入库、不进镜像；私钥权限见 §4。
- 安全组只放行 443，SSH 限制来源 IP；VM 开启自动安全更新。
- **没有反向代理意味着不能在代理层按路径限制管理控制台**。可选方案：用 `KC_HOSTNAME_ADMIN` 把管理控制台放到另一个仅内网/VPN 可达的地址，或用安全组/防火墙限制来源（未验证）。
- 为容器设置内存上限并预留 VM 内存，Keycloak 内存不足会被 OOM kill（未验证）。
- 镜像版本固定，定期关注 Keycloak 安全公告并按 §9 升级。

## 11. 故障排查

| 现象 | 常见原因 | 处理 |
|---|---|---|
| `up` 报 `required variable ... is missing` | `.env.prod` 缺必填变量 | 补变量，先 `config -q` |
| keycloak 反复重启，日志 `AccessDeniedException .../tls.key` | 私钥属主不是 uid 1000（属组 1000 不够） | 按 §4 `chown 1000:0` + `chmod 400`，`up -d --force-recreate keycloak` |
| keycloak 启动失败，日志提到证书/私钥读取失败 | `TLS_CERT_DIR` 下缺文件、文件名不对 | 检查文件名与 §4，`up -d --force-recreate keycloak` |
| 浏览器提示证书不受信任 | `tls.crt` 没含完整证书链，或域名与证书不匹配 | 使用 bundle 证书，核对证书域名 |
| 镜像拉取超时 | 国内访问 `quay.io`/Docker Hub 不稳定 | 见 §3 使用镜像加速、TCR 或离线导入 |
| keycloak 不 healthy，日志含 `password authentication failed` | 数据卷已用旧密码初始化，之后改了 `KC_DB_PASSWORD` | 把 `.env.prod` 改回原密码，或在库里 `ALTER ROLE` |
| 登录后跳转到错误域名或端口 | `KC_HOSTNAME` 与实际对外地址不一致 | 修正并重启 keycloak |
| 域名能解析但访问被拦截/超时 | 大陆地域未备案，或安全组未放行 443 | 检查备案状态与安全组 |
| 改了管理员密码环境变量不生效 | 管理员只在首次初始化库时创建 | 在控制台改，或用 Keycloak 命令行工具重置 |

## 12. 本 guideline 未覆盖（上线前需另行设计）

- **高可用/多实例**：本 compose 是单机单实例；多实例需共享缓存（`KC_CACHE=ispn` 与节点发现）、负载均衡与外部数据库，需单独验证。
- **数据库高可用与 PITR**：内置单实例 Postgres 无此能力；有此需求时应改用云数据库（TencentDB for PostgreSQL）。
- **realm 配置的版本化**：建议用 realm 导出文件或 Terraform/Keycloak 管理工具管理，而不是只在控制台手工配置。
- **邮件（SMTP）、自定义主题、自定义 SPI**：按需添加，SPI/主题要打进镜像并在 `kc.sh build` 阶段固化。
