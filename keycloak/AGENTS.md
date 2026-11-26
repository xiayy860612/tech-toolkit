# keycloak（本地 Keycloak 服务）

基于 docker-compose 的本地开发用 Keycloak（start-dev）+ PostgreSQL。仅用于本地，不用于生产。

## 技术栈与目录
- `docker-compose.yml`：`keycloak`（quay.io/keycloak/keycloak）与 `postgres`（数据卷 `keycloak-pgdata`）
- `.env.example`：环境变量示例；本地复制为 `.env`（不入库）
- `realms/`：启动时通过 `--import-realm` 导入的 realm JSON（只导入尚不存在的 realm）

## 命令
```bash
cp .env.example .env          # 首次，修改密码
docker compose config -q      # 校验 compose 文件（lint）
docker compose up -d          # 启动
docker compose ps             # 等待 keycloak 变为 healthy
docker compose logs -f keycloak
docker compose down           # 停止（保留数据）
```
清除全部数据时在 `down` 后加 `-v` 参数（会删除 Postgres 数据卷）。

- 管理控制台：http://localhost:8180（端口由 `KEYCLOAK_PORT` 控制），账号见 `.env`
- 健康检查：管理端口 9000 的 `/health/ready`（容器内）

## 生产形态部署（docker-compose.prod.yml）

完整的上线、运维、升级与排障步骤见 [`DEPLOYMENT.md`](DEPLOYMENT.md)。

拓扑：`keycloak`（`start --optimized`，**自带 TLS，直接对公网**，生产域名 `oidc.s2u2m.com`）→ `postgres`（PG 18，内置，不对外）。**没有 nginx/反向代理。**
本地验证与生产用同一份 compose，差异仅在 `.env.prod`。

- `Dockerfile`：先 `kc.sh build` 固化构建期选项（postgres、health、metrics），再 `start --optimized`
- TLS：证书目录 `TLS_CERT_DIR`（内含 `tls.crt` 完整证书链、`tls.key`）挂到容器 `/opt/keycloak/conf/certs`；`KC_HTTP_ENABLED=false`，只提供 HTTPS，宿主机 `KEYCLOAK_HTTPS_PORT`（生产 443）→ 容器 8443
- 对外地址由 `KC_HOSTNAME`（含协议）决定 issuer，生产为 `https://oidc.s2u2m.com`
- `/health`、`/metrics` 仅在容器内管理端口 9000（明文 HTTP，不映射到宿主机），公网端口上返回 404
- 内置 Postgres：卷 `pgdata`（PG 18 镜像挂 `/var/lib/postgresql`），库与账号由容器首次启动时按 `KC_DB_*` 创建，Keycloak 会等它 healthy；备份与升级需自行负责
- 管理员账号仅在首次初始化库时创建，之后改 `.env.prod` 不生效
- 国内 VM 拉不到 `quay.io` / Docker Hub 时，用 `KEYCLOAK_BASE_IMAGE`、`POSTGRES_IMAGE` 指向自己的镜像仓库

```bash
cp .env.prod.example .env.prod                   # 修改域名、密码、端口
./scripts/gen-local-cert.sh                      # 仅本地：生成自签名证书到 certs/（生产挂载真实证书，见 TLS_CERT_DIR）
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

本地验证：https://localhost:8443（自签名证书，浏览器需信任或用 `curl -k`）。预期：issuer 为 `https://localhost:8443/realms/master`；公网端口上 `/metrics`、`/health` 为 404；明文 HTTP 连不上；宿主机访问不到 9000。

生产上线还需自行补充（不在本地验证范围）：证书续期与重启流程、数据库备份与恢复演练、镜像版本固定与升级流程、密码走密钥管理而非明文 `.env.prod`、多实例时的 clustering（`KC_CACHE`）。

## 测试与覆盖率
无应用代码，不适用单元测试覆盖率；验证方式为 `docker compose config -q` 与启动后 `docker compose ps` 显示 healthy。

## 约定
- 禁止提交 `.env` 与真实密码；realm JSON 中不得含真实 client secret。
