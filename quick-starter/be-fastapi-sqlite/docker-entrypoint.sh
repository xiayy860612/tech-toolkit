#!/bin/bash
# Kids Homework Review - Backend Docker Entrypoint
# 此脚本在容器启动时运行

set -e

# 配置变量
PORT="${PORT:-8080}"
WORKERS="${WORKERS:-2}"
HOST="${HOST:-0.0.0.0}"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# 检查并安装依赖
install_dependencies() {
    log_info "检查并安装依赖..."

    if [[ -f "pyproject.toml" ]] && [[ -f "uv.lock" ]]; then
        # 使用 uv 同步依赖
        uv pip install --system --no-cache .
        log_info "依赖安装完成"
    else
        log_warn "未找到 pyproject.toml 或 uv.lock，跳过依赖安装"
    fi
}

# 运行数据库迁移
run_migrations() {
    log_info "运行数据库迁移..."
    uv run alembic upgrade head || log_warn "数据库迁移失败或跳过"
}

# 启动服务
start_service() {
    log_info "启动服务..."
    log_info "Host: $HOST, Port: $PORT, Workers: $WORKERS"

    exec uv run python run.py \
        --host "$HOST" \
        --port "$PORT" \
        --workers "$WORKERS"
}

# 主流程
main() {
    log_info "======================================"
    log_info "Kids Homework Review - Backend"
    log_info "======================================"

    install_dependencies
    run_migrations
    start_service
}

main "$@"
