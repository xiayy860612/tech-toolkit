#!/usr/bin/env bash
# 把 UAM 与 Admin Center（连同 PostgreSQL、Redis，入口由 ingress-nginx 提供）部署到本地 kind 集群（overlays/kind）。兼容 macOS 自带的 bash 3.2。
# 只服务 kind：云上直接 kubectl apply -k devops/k8s/overlays/cloud（Secret 由部署方准备，见 overlays/cloud/kustomization.yaml）。
#
# 用法：devops/k8s/scripts/deploy.sh [up|down|status|forward]
#   up       构建镜像 → 载入 kind → 首次生成密钥 Secret → 应用清单 → 等待就绪 → 建首个用户（幂等）
#   down     删除命名空间（连同数据卷）与本机保存的首个用户密码；不动集群本身
#   status   Pod、Service、NetworkPolicy 概览
#   forward  把入口转发到 http://127.0.0.1:${EDGE_PORT:-8088}（前台运行，Ctrl-C 结束）
#
# 环境变量：KIND_CLUSTER（默认 cka）、K8S_NAMESPACE（默认 tech-toolkit）、EDGE_PORT、
#           K8S_ADMIN_ACCOUNT（默认 admin）、K8S_ADMIN_PASSWORD（不设则随机生成，写入 ADMIN_PASSWORD_FILE，权限 0600）。
# 密钥与密码从不打印、不入库：服务密钥与数据库密码只存在 k8s Secret 里；重复执行 up 不会换密钥。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
# sha256_hex、clients_json 与本地 compose 栈共用（该库只定义函数，不含密钥）
# shellcheck source=../../local/scripts/service-key-lib.sh
source "$repo/devops/local/scripts/service-key-lib.sh"

cluster="${KIND_CLUSTER:-cka}"
ns="${K8S_NAMESPACE:-tech-toolkit}"
ctx="kind-$cluster"
edge_port="${EDGE_PORT:-8088}"
admin_account="${K8S_ADMIN_ACCOUNT:-admin}"
admin_name="${K8S_ADMIN_NAME:-系统管理员}"
admin_password_file="${ADMIN_PASSWORD_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/tech-toolkit/k8s-admin-password}"
uam_image="tech-toolkit/uam:local"
ac_image="tech-toolkit/admin-center:local"
# 清单里用到的第三方镜像：先在宿主机拉好再载入 kind，节点不必自己访问公网镜像仓库
third_party_images=(postgres:16 redis:7)

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
k() { kubectl --context "$ctx" -n "$ns" "$@"; }

usage() {
  echo "用法：$0 [up|down|status|forward]" >&2
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
#   uam-runtime           UAM 的连接与客户端清单（envFrom）
#   admin-center-runtime  Admin Center 的服务密钥（envFrom）
# 云上这些 Secret 由云密钥服务 / External Secrets / CI 创建，这个函数只服务 kind。
ensure_secrets() {
  # 旧布局（admin-center-service-key / uam-clients）已被 uam-runtime / admin-center-runtime 取代
  k delete secret admin-center-service-key uam-clients --ignore-not-found >/dev/null
  if ! secret_exists postgres-credentials; then
    log "生成数据库凭据 Secret"
    k create secret generic postgres-credentials \
      --from-literal=POSTGRES_DB=uam --from-literal=POSTGRES_USER=uam \
      --from-file=POSTGRES_PASSWORD=<(printf '%s' "$(openssl rand -hex 24)") >/dev/null
  fi
  # 服务密钥与 UAM_CLIENTS 的哈希必须成对：任一缺失就一起重新生成
  if ! secret_exists admin-center-runtime || ! secret_exists uam-runtime; then
    log "生成服务密钥（generate-service-key），写入 Secret admin-center-runtime / uam-runtime"
    local key hash db_password
    key="$(docker run --rm --network none "$uam_image" java -jar /app/uam.jar generate-service-key --client admin-center 2>/dev/null | tr -d '\n')"
    printf '%s' "$key" | grep -Eq '^uamk_[A-Za-z0-9_-]{43}$' || { echo "generate-service-key 的输出不是预期的密钥格式（不打印内容）" >&2; exit 1; }
    hash="sha256:$(sha256_hex "$key")"
    db_password="$(k get secret postgres-credentials -o jsonpath='{.data.POSTGRES_PASSWORD}' | base64 -d)"
    k create secret generic admin-center-runtime --from-file=UAM_SERVICE_KEY=<(printf '%s' "$key") \
      --dry-run=client -o yaml | k apply -f - >/dev/null
    k create secret generic uam-runtime \
      --from-literal=SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/uam \
      --from-literal=SPRING_DATASOURCE_USERNAME=uam \
      --from-file=SPRING_DATASOURCE_PASSWORD=<(printf '%s' "$db_password") \
      --from-literal=SPRING_DATA_REDIS_HOST=redis --from-literal=SPRING_DATA_REDIS_PORT=6379 \
      --from-file=UAM_CLIENTS=<(printf '%s' "$(clients_json admin-center "$hash")") \
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

# ingress controller（与云上相同的 Ingress 由它实现）。集群里没有就应用 cluster/ingress-nginx.yaml（固定版本），并等它就绪：
# Ingress 的准入 webhook 由 controller 提供，controller 没起来时应用 Ingress 会失败。节点自己拉取镜像（registry.k8s.io）。
ensure_ingress_controller() {
  if ! kubectl --context "$ctx" get namespace ingress-nginx >/dev/null 2>&1; then
    log "安装 ingress-nginx（cluster/ingress-nginx.yaml）"
    kubectl --context "$ctx" apply -f "$here/../cluster/ingress-nginx.yaml" >/dev/null
  fi
  kubectl --context "$ctx" -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=300s
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
  # 在已运行的 UAM Pod 里再起一个 cli 进程：数据库连接与密钥已在 Pod 的环境变量里；cli profile 不启动 Web、不连 Redis
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

action="${1:-up}"
[ "$#" -le 1 ] || usage
case "$action" in
  up)
    require_cluster
    build_and_load
    # Secret 要先于工作负载存在，命名空间因此先建（kustomize 里的同名 Namespace 再 apply 是幂等的）
    kubectl --context "$ctx" create namespace "$ns" --dry-run=client -o yaml | kubectl --context "$ctx" apply -f - >/dev/null
    ensure_secrets
    ensure_ingress_controller
    apply_manifests
    wait_ready
    init_admin
    log "完成。访问：$0 forward，然后打开 http://127.0.0.1:$edge_port"
    ;;
  down)
    require_cluster
    kubectl --context "$ctx" delete namespace "$ns" --ignore-not-found
    rm -f "$admin_password_file"
    ;;
  status)
    require_cluster
    k get pods,svc,pvc,networkpolicy -o wide
    ;;
  forward)
    require_cluster
    log "http://127.0.0.1:$edge_port → ingress-nginx（Ctrl-C 结束）"
    exec kubectl --context "$ctx" -n ingress-nginx port-forward --address 127.0.0.1 svc/ingress-nginx-controller "$edge_port:80"
    ;;
  *) usage ;;
esac
