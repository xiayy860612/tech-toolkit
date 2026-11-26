# Keycloak 生产部署 Guideline

适用：单机 Docker + docker-compose，使用**已存在的外部 PostgreSQL**。命令均在 `keycloak/` 目录下执行。
文中标注「已验证」的项在本地用同一份 compose 验证过；标注「未验证」的是建议，上线前需在目标环境自行演练。

## 1. 架构

```
Internet ──443──> nginx (TLS 终止) ──HTTP 8080──> Keycloak (start --optimized) ──JDBC──> 外部 PostgreSQL
                                                    └─ 9000 管理端口（/health、/metrics，仅容器网络内）
```

- 只有 nginx 的 80/443 映射到宿主机；Keycloak 的 8080/9000 不对宿主机暴露（已验证）。
- nginx 对 `/health*`、`/metrics*` 返回 404，健康与指标只走内网 9000（已验证）。
- 数据库不在本 compose 内，备份、高可用、升级由 DB 方负责。

## 2. 上线前准备清单

| 项 | 要求 |
|---|---|
| 主机 | Linux，Docker ≥ 24、Compose v2；开放 80/443 入站，到数据库的出站 |
| 域名 | 已解析到本机，如 `sso.example.com`；**对外地址一旦被客户端使用就不要再改**（会改变 issuer，令牌校验方都要跟着改） |
| 证书 | CA 签发，`tls.crt`（含完整证书链）与 `tls.key` 放同一目录，目录路径填 `TLS_CERT_DIR` |
| 数据库 | PostgreSQL 已就绪，库与账号已建（见 §3），主机到库网络可达 |
| 密码 | 管理员与数据库密码由密钥管理系统提供，不写进仓库 |

## 3. 准备数据库（一次性）

由 DBA 或有权限的人执行，Keycloak 独占一个库：

```sql
CREATE ROLE keycloak LOGIN PASSWORD '<强密码>';
CREATE DATABASE keycloak OWNER keycloak ENCODING 'UTF8';
```

- Keycloak 首次启动会在该库的 `public` schema 自动建表（约 89 张，已验证），账号需有建表权限。
- 与其他应用共用同一个库不推荐：表名与权限边界会互相影响。
- 要求 TLS 连库时，在 `.env.prod` 设 `KC_DB_URL_PARAMS=?sslmode=require`（未验证）。
- 连接数：Keycloak 默认连接池会占用若干连接，请按实例数与 `max_connections` 预留（未验证）。

## 4. 配置

```bash
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

| 变量 | 说明 | 生产示例 |
|---|---|---|
| `KC_HOSTNAME` | 对外完整地址（含协议；非默认端口要带端口），决定 issuer 与重定向 | `https://sso.example.com` |
| `SERVER_NAME` | nginx `server_name`，与域名一致 | `sso.example.com` |
| `PROXY_HTTPS_PORT` / `PROXY_HTTP_PORT` | 宿主机暴露端口 | `443` / `80` |
| `TLS_CERT_DIR` | 证书目录（内含 `tls.crt`、`tls.key`） | `/etc/keycloak/certs` |
| `KC_DB_HOST` / `KC_DB_PORT` | 外部库地址 | `pg.internal` / `5432` |
| `KC_DB_NAME` / `KC_DB_USERNAME` / `KC_DB_PASSWORD` | 预先创建的库与账号 | — |
| `KC_DB_URL_PARAMS` | 可选 JDBC 参数 | `?sslmode=require` |
| `KC_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` | 临时初始管理员 | 见 §6 |
| `KEYCLOAK_VERSION` | 镜像版本，**必须固定**，不要用浮动标签 | `26.4` |

> 注意：`KC_HOSTNAME` 与证书域名、`SERVER_NAME` 三者必须一致，否则登录跳转或证书校验会失败。

## 5. 首次部署

```bash
# 1. 校验配置（缺必填变量会在这里报错）
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q

# 2. 构建并启动
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build

# 3. 等待 keycloak 变为 healthy（首次含建表，约 1 分钟）
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f keycloak
```

## 6. 上线后立即做的事

1. 用 `.env.prod` 里的临时管理员登录 `https://<域名>/admin`。
2. 在 master realm 创建**个人/永久管理员**，开启 MFA，再**删除临时 bootstrap 管理员**。初始管理员只在首次初始化库时创建，之后改 `.env.prod` 不会生效。
3. 不要用 master realm 承载业务用户；为业务新建独立 realm。
4. 业务 realm 中：关闭不需要的注册/登录方式，设置密码策略与暴力破解保护，client 的 redirect URI 精确到具体地址，不用通配 `*`。

## 7. 验证清单

```bash
curl -s https://<域名>/realms/master/.well-known/openid-configuration   # issuer 应为 https://<域名>/realms/master
curl -s -o /dev/null -w "%{http_code}\n" https://<域名>/metrics         # 应为 404
curl -s -o /dev/null -w "%{http_code} -> %{redirect_url}\n" http://<域名>/   # 应为 301 跳 https
```

- 宿主机 `curl localhost:8080`、`localhost:9000` 应连接失败（端口未映射）。
- `ps` 中 keycloak 为 healthy，日志无 `ERROR`。

## 8. 日常运维

**证书续期**：替换 `TLS_CERT_DIR` 下的 `tls.crt`/`tls.key` 后重载 nginx，无需重启 Keycloak（未验证）：

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec proxy nginx -s reload
```

**升级 Keycloak**（单实例会有短暂停机）：
1. 阅读目标版本的 Release Notes / Upgrading Guide，尤其是破坏性变更。
2. **先备份数据库**（见下）。
3. 改 `.env.prod` 的 `KEYCLOAK_VERSION`，执行 `up -d --build`。Keycloak 启动时会自动迁移数据库结构。
4. 按 §7 验证，并抽查登录、token 签发、关键 client。
5. 回滚：**迁移后不能只回退镜像**，需要同时恢复升级前的数据库备份。

**备份**：备份对象是数据库（realm、用户、密钥都在库里）。例：

```bash
pg_dump -h <host> -U keycloak -Fc keycloak > keycloak-$(date +%F).dump
```
定期做，并**定期演练恢复**。备份文件含用户数据与密钥，加密存放、限制访问（未验证）。

**日志**：容器日志默认无限增长，请在 Docker `daemon.json` 或 compose 里配置日志轮转（如 `json-file` 的 `max-size`/`max-file`）（未验证）。

**监控**：`/health/ready` 与 `/metrics` 在 Keycloak 容器 9000 端口，不对外。如需 Prometheus 采集，让采集器加入 compose 网络 `keycloak-prod_backend` 访问 `keycloak:9000`，不要把 9000 映射到宿主机（未验证）。

## 9. 安全加固建议

- `.env.prod` 权限 600，不入库，不进镜像；密码来自密钥管理系统。
- 防火墙只放行 80/443；数据库只允许本机来源访问。
- 管理控制台（`/admin`、master realm）限制来源 IP 或仅走内网/VPN：可在 nginx 加 `location /admin` 的 `allow/deny`（未验证）。
- 前面如果还有负载均衡/CDN，需要在 nginx 配置 `real_ip`，否则日志与限流里看到的是 LB 的地址；`X-Forwarded-*` 只应由最外层可信代理设置。
- 为容器设置资源限制（内存/CPU），Keycloak 是 JVM，内存不足会被 OOM kill（未验证）。
- 镜像版本固定，定期关注 Keycloak 安全公告并按 §8 升级。

## 10. 故障排查

| 现象 | 常见原因 | 处理 |
|---|---|---|
| `up` 报 `required variable ... is missing` | `.env.prod` 缺必填变量 | 补变量，先 `config -q` |
| proxy 反复重启，日志 `cannot load certificate` | `TLS_CERT_DIR` 下没有 `tls.crt`/`tls.key`，或路径不存在（Docker 会自动建空目录） | 放入证书后 `up -d --force-recreate proxy` |
| keycloak 不 healthy，日志含 `connection refused`/`password authentication failed` | 库地址/端口/账号不对，或库未放行主机 | 在宿主机用 `psql` 同参数验证 |
| 登录后跳转到错误的域名或端口 | `KC_HOSTNAME` 与实际对外地址不一致 | 修正并重启 keycloak |
| 登录页提示 HTTPS required 或 issuer 为 http | nginx 没有透传 `X-Forwarded-*` | 检查 `nginx/default.conf.template` |
| 改了管理员密码环境变量不生效 | 管理员只在首次初始化库时创建 | 在控制台改，或用 Keycloak 命令行工具重置 |

## 11. 本 guideline 未覆盖（上线前需另行设计）

- **高可用/多实例**：本 compose 是单实例。多实例需要共享缓存（`KC_CACHE=ispn` 与节点发现）与前置负载均衡，需单独验证。
- **数据库高可用与 PITR**：由数据库侧提供。
- **realm 配置的版本化**：建议用 realm 导出文件或 Terraform/Keycloak 管理工具管理，而不是只在控制台手工配置。
- **邮件（SMTP）、自定义主题、自定义 SPI**：按需添加，SPI/主题要打进镜像并在 `kc.sh build` 阶段固化。
