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

## 测试与覆盖率
无应用代码，不适用单元测试覆盖率；验证方式为 `docker compose config -q` 与启动后 `docker compose ps` 显示 healthy。

## 约定
- 禁止提交 `.env` 与真实密码；realm JSON 中不得含真实 client secret。
