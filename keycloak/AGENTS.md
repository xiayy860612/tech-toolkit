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

拓扑：`nginx`（TLS 终止，唯一对外入口）→ `keycloak`（`start --optimized`，仅内网 HTTP）→ **已存在的外部 PostgreSQL**（compose 不包含数据库，也不管理其备份与升级）。
本地验证与生产用同一份 compose，差异仅在 `.env.prod`。

- `Dockerfile`：先 `kc.sh build` 固化构建期选项（postgres、health、metrics），再 `start --optimized`
- 对外地址由 `KC_HOSTNAME`（含协议与端口）决定 issuer；nginx 透传 `X-Forwarded-*`，Keycloak 用 `KC_PROXY_HEADERS=xforwarded`
- `/health`、`/metrics` 仅在容器内管理端口 9000，nginx 对其返回 404；8080/9000 不映射到宿主机
- 数据库连接由 `KC_DB_HOST` / `KC_DB_PORT` / `KC_DB_NAME` / `KC_DB_USERNAME` / `KC_DB_PASSWORD` 提供；需要 TLS 时设 `KC_DB_URL_PARAMS=?sslmode=require`
- 首次启动时 Keycloak 会在该库的 `public` schema 自动建表（约 89 张），账号需有建表权限；库与账号须**预先创建**：
  `CREATE ROLE keycloak LOGIN PASSWORD '<密码>'; CREATE DATABASE keycloak OWNER keycloak ENCODING 'UTF8';`
- 建议给 Keycloak 独占一个库；已有库被其他应用共用时，注意表名冲突与账号权限边界
- 管理员账号仅在首次初始化库时创建，之后改 `.env.prod` 不生效

```bash
cp .env.prod.example .env.prod                   # 修改域名、密码、端口
./scripts/gen-local-cert.sh                      # 仅本地：生成自签名证书到 certs/（生产挂载真实证书 tls.crt/tls.key，见 TLS_CERT_DIR）
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```
本地模拟“已存在的库”：用独立容器起一个 PG 并预建库与账号，`.env.prod` 里设 `KC_DB_HOST=host.docker.internal`、`KC_DB_PORT` 指向其映射端口（容器内通过 `extra_hosts` 访问宿主机）：
```bash
docker run -d --name existing-pg -e POSTGRES_PASSWORD=<管理员密码> -p 5433:5432 postgres:18-alpine
```
然后执行上文的 `CREATE ROLE` / `CREATE DATABASE`（`docker exec existing-pg psql -U postgres ...`）。

本地验证：https://localhost:8443（自签名证书，浏览器需信任或用 `curl -k`）。预期：issuer 为 `https://localhost:8443/realms/master`；`/metrics` 经代理 404；http 端口 301 到 https。

生产上线还需自行补充（不在本地验证范围）：真实域名与证书续期、外部数据库的备份与恢复演练、连接池与最大连接数规划、镜像版本固定与升级流程、密码走密钥管理而非明文 `.env.prod`、多实例时的 clustering（`KC_CACHE`）。

## 测试与覆盖率
无应用代码，不适用单元测试覆盖率；验证方式为 `docker compose config -q` 与启动后 `docker compose ps` 显示 healthy。

## 约定
- 禁止提交 `.env` 与真实密码；realm JSON 中不得含真实 client secret。
