#!/usr/bin/env bash
# quickstart S19 ①：对运行中的 E2E 栈做一次服务密钥轮换（FR-044、SC-016），不改任何被跟踪的文件。
#
# 用法：infra/local/scripts/rotate-key.sh rotate|restore
#
#   rotate   四步（每步打印；任一步失败立即以非 0 退出，并提示用 restore 回到初始状态）：
#            1 用真实的 generate-service-key 生成新密钥（只写进临时目录里 0600 的文件，从不打印）
#            2 把新哈希作为第二个 keyHashes 加进 UAM_CLIENTS，逐个重建 UAM 实例；Admin Center 仍用旧密钥
#            3 把 Admin Center 的 UAM_SERVICE_KEY 换成新密钥，逐个重建 Admin Center 实例
#            4 从 UAM_CLIENTS 删掉旧哈希，再逐个重建 UAM 实例
#            每次 UAM 重建后，直接在 UAM 容器内用密钥探测 GET /v1/users：第 2、3 步新旧密钥都是 200，
#            第 4 步旧密钥 401、新密钥 200。
#   restore  回到 infra/local/e2e.env 里的固定测试密钥与哈希（重置工具：同时重建 UAM 与 Admin Center，会有短暂中断）。
#
# 覆盖方式：生成临时 --env-file（后一个覆盖 e2e.env），不编辑 e2e.env，也不改编排文件。
# 前提：栈已按 e2e.env 启动（infra/local/scripts/bootstrap.sh up）；若栈停在上次轮换之后，先 restore。
#
# 环境变量（均有默认值）：
#   ROTATE_COMPOSE_FILE     编排文件，默认 infra/local/docker-compose.stack.yml（多实例栈用 docker-compose.scale.yml）
#   ROTATE_COMPOSE_PROJECT  compose 项目名（-p），默认按编排文件取：docker-compose.scale.yml 为 uam-scale，其余为 uam-e2e
#   ROTATE_UAM_SERVICES     UAM 服务名（空格分隔，逐个重建；多实例才是真正的滚动），默认 uam
#   ROTATE_ADMIN_SERVICES   Admin Center 服务名（空格分隔），默认 admin-center
#   ROTATE_CLIENT           客户端名，默认 admin-center
#   ROTATE_STEP_PAUSE       每步之后的等待秒数，默认 3（让持续访问的流量覆盖新旧并存期）
#   ROTATE_KEY_DIR          若给定，新密钥与各步 env 文件放在这个目录（调用方负责清理）；
#                           默认用 mktemp -d（0700）并在退出时删除——新密钥此后只存在于容器的环境变量里。
#
# 注意（单实例 E2E 栈）：默认栈只有一个 UAM，重建它会有几秒不可达（连接失败，Admin Center 按「服务暂不可用」处理，
# 不登出用户）；这不是密钥失败。要看到「0 个请求失败」的滚动效果，用多实例栈（见上面的环境变量）。
# 退出码：0 成功；1 轮换或探测失败；2 用法 / 环境错误。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=service-key-lib.sh
source "$here/service-key-lib.sh"

compose_file="${ROTATE_COMPOSE_FILE:-$local_dir/docker-compose.stack.yml}"
# 项目名显式传 -p（COMPOSE_PROJECT_NAME 会盖过编排文件的 name）：按编排文件取默认值，与文件里的 name 一致
case "$(basename "$compose_file")" in
  docker-compose.scale.yml) default_project=uam-scale ;;
  *) default_project=uam-e2e ;;
esac
compose_project="${ROTATE_COMPOSE_PROJECT:-$default_project}"
read -r -a uam_services <<<"${ROTATE_UAM_SERVICES:-uam}"
read -r -a admin_services <<<"${ROTATE_ADMIN_SERVICES:-admin-center}"
client="${ROTATE_CLIENT:-admin-center}"
pause="${ROTATE_STEP_PAUSE:-3}"
extra_env=()
work=""
own_work=0
current_step="准备"

# 经 env -u 去掉调用方 shell 里可能存在的同名变量：compose 里 shell 环境优先于 --env-file，会让覆盖失效
uam_compose() {
  env -u UAM_CLIENTS -u UAM_SERVICE_KEY docker compose -p "$compose_project" -f "$compose_file" \
    --env-file "$local_dir/e2e.env" ${extra_env[@]+"${extra_env[@]}"} "$@"
}

# shellcheck disable=SC2329  # 经 trap 调用
on_exit() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "失败：在「$current_step」。栈可能停在中间状态；运行 $0 restore 回到 e2e.env 的固定测试密钥。" >&2
  fi
  if [ "$own_work" -eq 1 ] && [ -n "$work" ]; then
    rm -rf "$work"
  fi
}
trap on_exit EXIT

step() { current_step="$1"; key_log "$1"; }

# 在 UAM 容器内用 <密钥> 调 GET /v1/users，返回 HTTP 状态码；密钥经标准输入传入，不出现在命令行或环境里
probe() {
  local service="$1" key="$2"
  # shellcheck disable=SC2016  # 单引号里的脚本在容器内展开，这是有意的
  printf '%s\n' "$key" | uam_compose exec -T "$service" bash -c '
    read -r key
    exec 3<>/dev/tcp/127.0.0.1/8080
    printf "GET /v1/users HTTP/1.0\r\nHost: uam\r\nAuthorization: Bearer %s\r\n\r\n" "$key" >&3
    read -r _ status _ <&3
    printf "%s" "$status"'
}

# expect_status <描述> <期望状态码> <密钥>：对每个 UAM 实例探测
expect_status() {
  local label="$1" expected="$2" key="$3" service got
  for service in "${uam_services[@]}"; do
    got="$(probe "$service" "$key")"
    if [ "$got" != "$expected" ]; then
      echo "探测失败：$service 对「$label」返回 ${got:-无响应}，期望 $expected" >&2
      return 1
    fi
    key_log "  $service：$label → $got"
  done
}

# 逐个重建（--no-deps：不动 PostgreSQL / Redis；--wait：等健康检查通过再继续下一个）
recreate() {
  local service
  for service in "$@"; do
    key_log "  重建 $service"
    uam_compose up -d --no-deps --wait "$service"
  done
}

pause_between() { [ "$pause" -le 0 ] || sleep "$pause"; }

old_key="$(sed -n 's/^UAM_SERVICE_KEY=//p' "$local_dir/e2e.env")"
require_stack_up() {
  local service
  for service in "${uam_services[@]}" "${admin_services[@]}"; do
    if [ -z "$(uam_compose ps --status running --quiet "$service" 2>/dev/null)" ]; then
      echo "服务 $service 未在运行：先启动栈（infra/local/scripts/bootstrap.sh up）" >&2
      exit 2
    fi
  done
}

do_rotate() {
  require_stack_up
  if [ -n "${ROTATE_KEY_DIR:-}" ]; then
    work="$ROTATE_KEY_DIR"
    mkdir -p "$work"
    chmod 700 "$work"
  else
    work="$(mktemp -d)"
    own_work=1
  fi
  local old_hash new_key new_hash
  old_hash="sha256:$(sha256_hex "$old_key")"

  step "0/4 起始检查：e2e.env 的旧密钥必须当前有效"
  expect_status "旧密钥" 200 "$old_key"

  step "1/4 用 generate-service-key 生成新密钥"
  # 多实例栈没有名为 uam 的服务：默认用第一个 UAM 服务来跑一次性命令（可用 UAM_KEY_SERVICE 覆盖）
  UAM_KEY_SERVICE="${UAM_KEY_SERVICE:-${uam_services[0]}}"
  generate_service_key "$client" "$work/new.key"
  new_hash="$GENERATED_HASH"
  new_key="$(tr -d '\n' <"$work/new.key")"
  key_log "  新密钥已写入 $work/new.key（权限 0600，不打印）；新哈希 $new_hash"

  step "2/4 加第二个哈希并逐个重建 UAM（Admin Center 仍用旧密钥）"
  write_env_override "$work/step2.env" "$old_key" "$(clients_json "$client" "$old_hash" "$new_hash")"
  extra_env=(--env-file "$work/step2.env")
  recreate "${uam_services[@]}"
  expect_status "旧密钥" 200 "$old_key"
  expect_status "新密钥" 200 "$new_key"
  pause_between

  step "3/4 Admin Center 换新密钥并逐个重建"
  write_env_override "$work/step3.env" "$new_key" "$(clients_json "$client" "$old_hash" "$new_hash")"
  extra_env=(--env-file "$work/step3.env")
  recreate "${admin_services[@]}"
  pause_between

  step "4/4 删旧哈希并逐个重建 UAM"
  write_env_override "$work/step4.env" "$new_key" "$(clients_json "$client" "$new_hash")"
  extra_env=(--env-file "$work/step4.env")
  recreate "${uam_services[@]}"
  expect_status "新密钥" 200 "$new_key"
  expect_status "旧密钥（已撤销）" 401 "$old_key"

  key_log "轮换完成：Admin Center 与 UAM 现在使用新密钥；运行 $0 restore 可回到 e2e.env 的固定测试密钥。"
  if [ "$own_work" -eq 1 ]; then
    key_log "临时目录已删除；新密钥此后只存在于容器的环境变量里。"
  fi
}

do_restore() {
  step "restore：回到 e2e.env 的固定测试密钥与哈希"
  extra_env=()
  uam_compose up -d --no-deps --wait "${uam_services[@]}" "${admin_services[@]}"
  expect_status "e2e.env 的密钥" 200 "$old_key"
  key_log "已恢复。"
}

case "${1:-}" in
  rotate) do_rotate ;;
  restore) do_restore ;;
  *)
    echo "用法：$0 rotate|restore（环境变量见脚本头部注释）" >&2
    exit 2
    ;;
esac
