#!/usr/bin/env bash
# 构建镜像并启动完整 E2E 栈（PostgreSQL、Redis、UAM、Admin Center）。
# 配置来自 infra/local/e2e.env（仅限本地 E2E 的固定测试密钥）。
#
# 用法：infra/local/scripts/bootstrap.sh [up|down] [--generated-key]
#   默认           用 e2e.env 里的固定测试密钥与哈希（E2E 的用例依赖这把固定密钥）。
#   --generated-key（仅 up，可选）改用 UAM 的 generate-service-key 现生成密钥：先构建 UAM 镜像，跑命令生成密钥
#                  （不连库、不连 Redis），把它的哈希写进 UAM_CLIENTS、密钥交给 Admin Center，经临时 --env-file
#                  覆盖 e2e.env（不改任何被跟踪的文件）。密钥不打印，也不落盘：临时目录在退出时删除，
#                  之后只存在于容器的环境变量里；需要留一份时设 BOOTSTRAP_KEY_FILE=<路径>（写成 0600）。
#                  依赖固定密钥的 E2E 用例不要用它。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 显式 -p：开发者 shell 里的 COMPOSE_PROJECT_NAME 会盖过编排文件的 name，`down -v` 可能误删别的项目（与 docker-compose.stack.yml 的 name 保持一致）
compose=(docker compose -p uam-e2e -f "$here/../docker-compose.stack.yml" --env-file "$here/../e2e.env")
# shellcheck source=service-key-lib.sh
source "$here/service-key-lib.sh"
uam_compose() { "${compose[@]}" "$@"; }

# 首个用户（init-admin 仅在没有任何用户时创建，幂等）。仅限本地 E2E 的假密码，与 tests/e2e/support/fixtures.ts 的 ADMIN 一致；
# 密码只经标准输入传给 UAM 的部署期命令。
admin_account="${E2E_ADMIN_ACCOUNT:-admin}"
admin_password="${E2E_ADMIN_PASSWORD:-E2e-Admin-Pass-1}"
admin_name="${E2E_ADMIN_NAME:-系统管理员}"

init_admin() {
  printf '%s\n' "$admin_password" | "${compose[@]}" run --rm -T -e SPRING_PROFILES_ACTIVE=cli uam \
    java -jar /app/uam.jar init-admin --account-name "$admin_account" --user-name "$admin_name" >/dev/null
}

action="${1:-up}"
[ "$#" -eq 0 ] || shift
generated_key=0
for arg in "$@"; do
  case "$arg" in
    --generated-key) generated_key=1 ;;
    *)
      echo "用法：$0 [up|down] [--generated-key]" >&2
      exit 2
      ;;
  esac
done

use_generated_key() {
  local work key_file
  work="$(mktemp -d)"
  # shellcheck disable=SC2064  # 现在就展开 $work；EXIT trap 在脚本退出（不是函数返回）时才触发
  trap "rm -rf '$work'" EXIT
  key_file="$work/service.key"
  echo "生成服务密钥（generate-service-key；需要先构建 UAM 镜像）" >&2
  "${compose[@]}" build uam
  generate_service_key admin-center "$key_file"
  write_env_override "$work/override.env" "$(tr -d '\n' <"$key_file")" "$(clients_json admin-center "$GENERATED_HASH")"
  if [ -n "${BOOTSTRAP_KEY_FILE:-}" ]; then
    install -m 600 "$key_file" "$BOOTSTRAP_KEY_FILE"
    echo "密钥已写入 $BOOTSTRAP_KEY_FILE（权限 0600）" >&2
  fi
  # 后一个 --env-file 覆盖 e2e.env；env -u 去掉调用方 shell 里同名变量（compose 里 shell 环境优先）
  compose=(env -u UAM_CLIENTS -u UAM_SERVICE_KEY "${compose[@]}" --env-file "$work/override.env")
  bring_up
}

bring_up() {
  "${compose[@]}" up -d --build --wait
  init_admin
  echo "栈已就绪：Admin Center http://127.0.0.1:3000（UAM 只在栈内网络可达；首个用户账号：$admin_account）"
}

case "$action" in
  up)
    if [ "$generated_key" -eq 1 ]; then
      use_generated_key
    else
      bring_up
    fi
    ;;
  down)
    [ "$generated_key" -eq 0 ] || { echo "--generated-key 只用于 up" >&2; exit 2; }
    "${compose[@]}" down -v
    ;;
  *)
    echo "用法：$0 [up|down] [--generated-key]" >&2
    exit 2
    ;;
esac
