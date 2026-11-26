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

拓扑：`nginx`（TLS 终止，唯一对外入口）→ `keycloak`（`start --optimized`，仅内网 HTTP）→ `postgres`（PG 18，不对外）。
本地验证与生产用同一份 compose，差异仅在 `.env.prod`。

- `Dockerfile`：先 `kc.sh build` 固化构建期选项（postgres、health、metrics），再 `start --optimized`
- 对外地址由 `KC_HOSTNAME`（含协议与端口）决定 issuer；nginx 透传 `X-Forwarded-*`，Keycloak 用 `KC_PROXY_HEADERS=xforwarded`
- `/health`、`/metrics` 仅在容器内管理端口 9000，nginx 对其返回 404；8080/9000 不映射到宿主机
- PG 18 镜像的数据目录为 `/var/lib/postgresql/18/docker`，卷挂 `/var/lib/postgresql`；从 PG 17 卷升级需走 pg_upgrade/dump，不能直接复用
- 管理员账号仅在首次初始化库时创建，之后改 `.env.prod` 不生效

```bash
cp .env.prod.example .env.prod                   # 修改域名、密码、端口
./scripts/gen-local-cert.sh                      # 仅本地：生成自签名证书到 certs/（生产挂载真实证书 tls.crt/tls.key，见 TLS_CERT_DIR）
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```
本地验证：https://localhost:8443（自签名证书，浏览器需信任或用 `curl -k`）。预期：issuer 为 `https://localhost:8443/realms/master`；`/metrics` 经代理 404；http 端口 301 到 https。

生产上线还需自行补充（不在本地验证范围）：真实域名与证书续期、数据库备份与恢复演练、镜像版本固定与升级流程、密码走密钥管理而非明文 `.env.prod`、多实例时的 clustering（`KC_CACHE`）。

## 测试与覆盖率
无应用代码，不适用单元测试覆盖率；验证方式为 `docker compose config -q` 与启动后 `docker compose ps` 显示 healthy。

## 约定
- 禁止提交 `.env` 与真实密码；realm JSON 中不得含真实 client secret。
