#!/usr/bin/env bash
# 把 UAM 与 Admin Center（连同 PostgreSQL、Redis，入口由 Envoy Gateway（Gateway API）提供）部署到本地 kind 集群（overlays/kind）。兼容 macOS 自带的 bash 3.2。
# 只服务 kind：云上直接 kubectl apply -k devops/k8s/overlays/cloud（Secret 由部署方准备，见 overlays/cloud/kustomization.yaml）。
#
# 用法：devops/k8s/scripts/deploy.sh [up|down|status|forward|test-account]
#   up       构建镜像 → 载入 kind → 首次生成数据库凭据与 UAM 连接 Secret → 应用清单 → 等待就绪 → 建首个用户（幂等）
#   down     删除命名空间（连同数据卷）与本机保存的首个用户 / 测试账号密码；不动集群本身
#   test-account  建专用的本地测试账号（挂在首个用户下，随机密码写入 TEST_PASSWORD_FILE，0600；已存在则跳过）
#   status   Pod、Service、NetworkPolicy 概览
#   forward  把入口转发到 http://127.0.0.1:${EDGE_PORT:-8088}（前台运行，Ctrl-C 结束）
#
# 环境变量：KIND_CLUSTER（默认 cka）、K8S_NAMESPACE（默认 tech-toolkit）、EDGE_PORT、
#           K8S_ADMIN_ACCOUNT（默认 admin）、K8S_ADMIN_PASSWORD（不设则随机生成，写入 ADMIN_PASSWORD_FILE，权限 0600）。
# 密码从不打印、不入库：数据库密码只存在 k8s Secret 里；重复执行 up 不会换密码。UAM 不认证调用方，保护靠 NetworkPolicy。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"

cluster="${KIND_CLUSTER:-cka}"
ns="${K8S_NAMESPACE:-tech-toolkit}"
ctx="kind-$cluster"
edge_port="${EDGE_PORT:-8088}"
envoy_gateway_version="v1.9.3"
envoy_gateway_url="https://github.com/envoyproxy/gateway/releases/download/$envoy_gateway_version/install.yaml"
admin_account="${K8S_ADMIN_ACCOUNT:-admin}"
admin_name="${K8S_ADMIN_NAME:-系统管理员}"
admin_password_file="${ADMIN_PASSWORD_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/tech-toolkit/k8s-admin-password}"
test_account_name="${K8S_TEST_ACCOUNT:-k8s-test}"
test_password_file="${TEST_PASSWORD_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/tech-toolkit/k8s-test-account-password}"
uam_image="tech-toolkit/uam:local"
ac_image="tech-toolkit/admin-center:local"
# 清单里用到的第三方镜像：先在宿主机拉好再载入 kind，节点不必自己访问公网镜像仓库
third_party_images=(postgres:16 redis:7)

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
k() { kubectl --context "$ctx" -n "$ns" "$@"; }

usage() {
  echo "用法：$0 [up|down|status|forward|test-account]" >&2
  exit 2
}

require_cluster() {
  command -v kind >/dev/null || { echo "缺少 kind" >&2; exit 2; }
  command -v kubectl >/dev/null || { echo "缺少 kubectl" >&2; exit 2; }
  kind get clusters 2>/dev/null | grep -qx "$cluster" || { echo "kind 集群 $cluster 不存在（KIND_CLUSTER 可改）" >&2; exit 2; }
}

build_and_load() {
  log "构建镜像（上下文为仓库根，Dockerfile 与 CI 相同）"
  docker build -q -f "$repo/apps/uam/Dockerfile" -t "$uam_image" "$repo" >/dev/null
  docker build -q -f "$repo/apps/admin-center/Dockerfile" -t "$ac_image" "$repo" >/dev/null
  local img
  for img in "${third_party_images[@]}"; do
    docker image inspect "$img" >/dev/null 2>&1 || docker pull -q "$img" >/dev/null
  done
  # 不用 `kind load docker-image`：Docker 29 的 containerd 镜像存储下，它按 --all-platforms 导入会因缺少其他平台的内容而失败。
  # 改为只导出节点所在平台（与 Docker 守护进程同架构）的 tar，再用 image-archive 导入
  local platform tmp
  platform="linux/$(docker version --format '{{.Server.Arch}}')"
  tmp="$(mktemp -d)"
  log "载入 kind 集群 $cluster（$platform）"
  for img in "$uam_image" "$ac_image" "${third_party_images[@]}"; do
    docker save --platform "$platform" -o "$tmp/image.tar" "$img"
    kind load image-archive --name "$cluster" "$tmp/image.tar" >/dev/null
  done
  rm -rf "$tmp"
}

secret_exists() { k get secret "$1" >/dev/null 2>&1; }

# Secret 契约见 docs/system-design/system/README.md §4.2.1：
#   postgres-credentials  集群内 PostgreSQL 用（components/in-cluster-data）
#   uam-runtime           UAM 的数据库与 Redis 连接（envFrom）
#   Admin Center 没有必填 Secret
# 云上这些 Secret 由云密钥服务 / External Secrets / CI 创建，这个函数只服务 kind。
ensure_secrets() {
  # 旧布局（admin-center-service-key / uam-clients / admin-center-runtime）里的服务密钥已随 clients 功能移除
  k delete secret admin-center-service-key uam-clients admin-center-runtime --ignore-not-found >/dev/null
  if ! secret_exists postgres-credentials; then
    log "生成数据库凭据 Secret"
    k create secret generic postgres-credentials \
      --from-literal=POSTGRES_DB=uam --from-literal=POSTGRES_USER=uam \
      --from-file=POSTGRES_PASSWORD=<(printf '%s' "$(openssl rand -hex 24)") >/dev/null
  fi
  # 旧版 uam-runtime 里带着 UAM_CLIENTS：一并重建（数据库密码仍取自 postgres-credentials，不变）
  if ! secret_exists uam-runtime || k get secret uam-runtime -o jsonpath='{.data.UAM_CLIENTS}' | grep -q .; then
    log "写入 Secret uam-runtime"
    local db_password
    db_password="$(k get secret postgres-credentials -o jsonpath='{.data.POSTGRES_PASSWORD}' | base64 -d)"
    k create secret generic uam-runtime \
      --from-literal=SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/uam \
      --from-literal=SPRING_DATASOURCE_USERNAME=uam \
      --from-file=SPRING_DATASOURCE_PASSWORD=<(printf '%s' "$db_password") \
      --from-literal=SPRING_DATA_REDIS_HOST=redis --from-literal=SPRING_DATA_REDIS_PORT=6379 \
      --dry-run=client -o yaml | k apply -f - >/dev/null
  fi
}

apply_manifests() {
  log "应用清单"
  kubectl --context "$ctx" apply -k "$here/../overlays/kind" >/dev/null
  # 镜像用固定的 :local 标签，内容变了清单却没变，所以显式滚动一次让 Pod 用上刚载入的新镜像
  k rollout restart deployment/uam deployment/admin-center >/dev/null
}

wait_ready() {
  log "等待就绪"
  k rollout status statefulset/postgres --timeout=180s
  k rollout status statefulset/redis --timeout=180s
  k rollout status deployment/uam --timeout=300s
  k rollout status deployment/admin-center --timeout=180s
}

# Gateway API 入口：Envoy Gateway（连同 Gateway API 的 CRD，固定版本）+ GatewayClass。集群里没有就安装并等它就绪。
# 安装清单约 4 MB（主要是 CRD），不放进仓库，按固定版本从官方 release 下载；CRD 很大，必须用 server-side apply。
# 节点自己拉取镜像（docker.io/envoyproxy）。
ensure_gateway_controller() {
  if ! kubectl --context "$ctx" get namespace envoy-gateway-system >/dev/null 2>&1; then
    log "安装 Envoy Gateway $envoy_gateway_version（含 Gateway API CRD）"
    kubectl --context "$ctx" apply --server-side -f "$envoy_gateway_url" >/dev/null
  fi
  kubectl --context "$ctx" -n envoy-gateway-system wait --for=condition=Available deployment/envoy-gateway --timeout=300s >/dev/null
  kubectl --context "$ctx" apply -f "$here/../cluster/envoy-gateway-class.yaml" >/dev/null
}

# Envoy Gateway 为 Gateway 创建的数据面 Service（在 envoy-gateway-system 里，名字带哈希，只能按标签找）
gateway_proxy_service() {
  kubectl --context "$ctx" -n envoy-gateway-system get service \
    -l "gateway.envoyproxy.io/owning-gateway-name=admin-center,gateway.envoyproxy.io/owning-gateway-namespace=$ns" \
    -o jsonpath='{.items[0].metadata.name}'
}

init_admin() {
  # init-admin 仅在没有任何用户时创建首个用户，幂等；密码只经标准输入。已有用户时密码文件保持原样（可能是上次生成的）
  local password="${K8S_ADMIN_PASSWORD:-}"
  if [ -z "$password" ] && [ -r "$admin_password_file" ]; then
    password="$(cat "$admin_password_file")"
  fi
  if [ -z "$password" ]; then
    password="$(openssl rand -base64 18 | tr -d '/+=')Aa1"
  fi
  mkdir -p "$(dirname "$admin_password_file")"
  (umask 077 && printf '%s' "$password" >"$admin_password_file")
  # 在已运行的 UAM Pod 里再起一个 cli 进程：数据库连接已在 Pod 的环境变量里；cli profile 不启动 Web、不连 Redis
  # cli 的日志写标准错误且很长：只在失败时才展示
  local err
  err="$(mktemp)"
  if ! printf '%s\n' "$password" | k exec -i deploy/uam -- env SPRING_PROFILES_ACTIVE=cli \
    java -jar /app/uam.jar init-admin --account-name "$admin_account" --user-name "$admin_name" >/dev/null 2>"$err"; then
    cat "$err" >&2
    rm -f "$err"
    exit 1
  fi
  rm -f "$err"
  log "首个用户账号：$admin_account；密码在 $admin_password_file"
}

# 专用的本地测试账号：给首个用户追加第二个账号（add-account，部署期命令），用来跑登录 / 会话 / 滚动发布的验证，
# 这样验证不必读首个用户的密码。密码随机生成，只写进本机文件（0600），从不打印。
# 账号挂在首个用户名下，权限与之相同；只用于本地 kind：down 会删掉密码文件，账号随数据卷一起消失。
test_account() {
  if [ -r "$test_password_file" ]; then
    log "测试账号 $test_account_name 已存在；密码在 $test_password_file"
    return
  fi
  local password err
  password="$(openssl rand -base64 18 | tr -d '/+=')Aa1"
  mkdir -p "$(dirname "$test_password_file")"
  (umask 077 && printf '%s' "$password" >"$test_password_file")
  err="$(mktemp)"
  if ! printf '%s\n' "$password" | k exec -i deploy/uam -- env SPRING_PROFILES_ACTIVE=cli \
    java -jar /app/uam.jar add-account --account-name-of "$admin_account" --account-name "$test_account_name" >/dev/null 2>"$err"; then
    cat "$err" >&2
    rm -f "$err" "$test_password_file"
    exit 1
  fi
  rm -f "$err"
  log "测试账号 $test_account_name 已创建（挂在 $admin_account 用户下）；密码在 $test_password_file"
}

action="${1:-up}"
[ "$#" -le 1 ] || usage
case "$action" in
  up)
    require_cluster
    build_and_load
    # Secret 要先于工作负载存在，命名空间因此先建（kustomize 里的同名 Namespace 再 apply 是幂等的）
    kubectl --context "$ctx" create namespace "$ns" --dry-run=client -o yaml | kubectl --context "$ctx" apply -f - >/dev/null
    ensure_secrets
    ensure_gateway_controller
    apply_manifests
    wait_ready
    init_admin
    log "完成。访问：$0 forward，然后打开 http://127.0.0.1:$edge_port"
    ;;
  down)
    require_cluster
    kubectl --context "$ctx" delete namespace "$ns" --ignore-not-found
    rm -f "$admin_password_file" "$test_password_file"
    ;;
  test-account)
    require_cluster
    test_account
    ;;
  status)
    require_cluster
    k get pods,svc,pvc,networkpolicy -o wide
    ;;
  forward)
    require_cluster
    proxy_svc="$(gateway_proxy_service)"
    [ -n "$proxy_svc" ] || { echo "没有找到 Gateway 的代理 Service：先执行 up" >&2; exit 2; }
    log "http://127.0.0.1:$edge_port → svc/$proxy_svc（Envoy Gateway，Ctrl-C 结束）"
    exec kubectl --context "$ctx" -n envoy-gateway-system port-forward --address 127.0.0.1 "svc/$proxy_svc" "$edge_port:80"
    ;;
  *) usage ;;
esac
