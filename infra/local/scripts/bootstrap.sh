#!/usr/bin/env bash
# 构建镜像并启动完整 E2E 栈（PostgreSQL、Redis、UAM、Admin Center）。
# 配置来自 infra/local/e2e.env（仅限本地 E2E 的固定测试密钥）。
# 用法：infra/local/scripts/bootstrap.sh [up|down]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose=(docker compose -f "$here/../docker-compose.stack.yml" --env-file "$here/../e2e.env")

# 首个用户（init-admin 仅在没有任何用户时创建，幂等）。仅限本地 E2E 的假密码，与 tests/e2e/support/fixtures.ts 的 ADMIN 一致；
# 密码只经标准输入传给 UAM 的部署期命令。
admin_account="${E2E_ADMIN_ACCOUNT:-admin}"
admin_password="${E2E_ADMIN_PASSWORD:-E2e-Admin-Pass-1}"
admin_name="${E2E_ADMIN_NAME:-系统管理员}"

init_admin() {
  printf '%s\n' "$admin_password" | "${compose[@]}" run --rm -T -e SPRING_PROFILES_ACTIVE=cli uam \
    java -jar /app/uam.jar init-admin --account-name "$admin_account" --user-name "$admin_name" >/dev/null
}

case "${1:-up}" in
  up)
    "${compose[@]}" up -d --build --wait
    init_admin
    echo "栈已就绪：Admin Center http://127.0.0.1:3000（UAM 只在栈内网络可达；首个用户账号：$admin_account）"
    ;;
  down)
    "${compose[@]}" down -v
    ;;
  *)
    echo "用法：$0 [up|down]" >&2
    exit 2
    ;;
esac
