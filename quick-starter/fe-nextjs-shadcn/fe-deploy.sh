#!/bin/bash
# Kids Homework Review - Frontend Deployment Script
# 此脚本在远程服务器上运行，用于部署前端服务

set -e

# 配置变量（可通过环境变量覆盖）
DEPLOY_DIR="${DEPLOY_DIR:-$HOME/kids-homework-review/frontend}"
PACKAGE_NAME="${PACKAGE_NAME:-frontend-package.tar.gz}"
CHECKSUM_FILE="${CHECKSUM_FILE:-frontend-package.sha256}"
CONTAINER_NAME="${CONTAINER_NAME:-kids-frontend}"
BASE_IMAGE="${BASE_IMAGE:-node:20-alpine}"
PORT="${PORT:-3000}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 验证校验和
verify_checksum() {
    local package=$1
    local checksum_file=$2

    log_info "验证校验和..."
    if sha256sum -c "$checksum_file" 2>/dev/null; then
        log_info "校验和验证通过"
        return 0
    else
        log_error "校验和验证失败"
        return 1
    fi
}

# 解压部署包
extract_package() {
    local package=$1

    log_info "解压部署包..."
    mkdir -p "$DEPLOY_DIR"
    tar -xzf "$package" -C "$DEPLOY_DIR"
    log_info "解压完成"
}

# 拉取基础镜像
pull_base_image() {
    log_info "拉取基础镜像..."
    docker pull "$BASE_IMAGE"
    log_info "基础镜像准备完成"
}

# 准备环境配置
prepare_env() {
    log_info "准备环境配置..."
    cd "$DEPLOY_DIR"

    # 创建 .env.local 文件（如果不存在）
    if [[ ! -f .env.local ]]; then
        if [[ -f .env.local.example ]]; then
            cp .env.local.example .env.local
            log_warn ".env.local 文件不存在，已从 .env.local.example 创建，请编辑配置"
        fi
    fi
}

# 停止并移除旧容器
stop_old_container() {
    log_info "停止旧容器..."
    if docker ps -a -q -f name="$CONTAINER_NAME" | grep -q .; then
        docker stop "$CONTAINER_NAME" || true
        docker rm "$CONTAINER_NAME" || true
        log_info "旧容器已停止并移除"
    else
        log_info "没有找到运行中的容器"
    fi
}

# 启动新容器
start_new_container() {
    log_info "启动新容器..."

    # 确保 docker-entrypoint.sh 可执行
    chmod +x "${DEPLOY_DIR}/docker-entrypoint.sh"

    # 启动服务（挂载代码目录）
    docker run -d \
        --name "$CONTAINER_NAME" \
        --restart unless-stopped \
        -p "${PORT}:3000" \
        -v "${DEPLOY_DIR}:/app" \
        -e "PORT=${PORT}" \
        -e "HOST=0.0.0.0" \
        -e "NODE_ENV=production" \
        --workdir /app \
        --entrypoint /app/docker-entrypoint.sh \
        "$BASE_IMAGE"

    log_info "新容器已启动"
}

# 清理旧镜像
cleanup_old_images() {
    log_info "清理未使用的 Docker 镜像..."
    docker image prune -f
}

# 显示容器状态
show_status() {
    log_info "容器状态:"
    docker ps -f name="$CONTAINER_NAME"
}

# 主流程
main() {
    log_info "======================================"
    log_info "Kids Homework Review - Frontend Deploy"
    log_info "======================================"

    # 检查参数
    if [[ $# -lt 1 ]]; then
        log_error "用法: $0 <package.tar.gz> [checksum.sha256]"
        exit 1
    fi

    PACKAGE_PATH="$1"
    CHECKSUM_PATH="${2:-${PACKAGE_PATH}.sha256}"

    # 检查文件存在
    if [[ ! -f "$PACKAGE_PATH" ]]; then
        log_error "部署包不存在: $PACKAGE_PATH"
        exit 1
    fi

    # 执行部署流程
    # verify_checksum "$PACKAGE_PATH" "$CHECKSUM_PATH"
    extract_package "$PACKAGE_PATH"
    pull_base_image
    prepare_env
    stop_old_container
    start_new_container
    cleanup_old_images
    show_status

    log_info "======================================"
    log_info "部署完成!"
    log_info "======================================"
}

# 运行主流程
main "$@"
