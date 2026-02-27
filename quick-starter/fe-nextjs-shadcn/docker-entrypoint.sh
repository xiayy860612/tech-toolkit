#!/bin/sh
# Kids Homework Review - Frontend Docker Entrypoint
# 此脚本在容器启动时运行

set -e

# 配置变量
PORT="${PORT:-3000}"
HOST="${HOST:-0.0.0.0}"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
    printf "${GREEN}[INFO]${NC} %s\n" "$1"
}

log_warn() {
    printf "${YELLOW}[WARN]${NC} %s\n" "$1"
}

# 检查并安装依赖
install_dependencies() {
    log_info "检查并安装依赖..."

    if [ -f "package.json" ] && [ -f "package-lock.json" ]; then
        # 使用 npm ci 安装依赖（仅生产依赖）
        npm ci --only=production --no-audit --no-fund
        log_info "依赖安装完成"
    else
        log_warn "未找到 package.json 或 package-lock.json，跳过依赖安装"
    fi
}

# 启动服务
start_service() {
    log_info "启动 Next.js 服务..."
    log_info "Host: $HOST, Port: $PORT"

    # 设置 Node.js 生产环境
    export NODE_ENV=production

    # 启动 Next.js standalone 服务器
    exec node standalone/server.js
}

# 主流程
main() {
    log_info "======================================"
    log_info "Kids Homework Review - Frontend"
    log_info "======================================"

    install_dependencies
    start_service
}

main "$@"
