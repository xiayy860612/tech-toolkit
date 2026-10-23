#!/usr/bin/env bash
# 构建镜像并启动完整 E2E 栈（PostgreSQL、Redis、UAM、Admin Center）。
# 配置来自 infra/local/e2e.env（仅限本地 E2E 的固定测试密钥）。
# 用法：infra/local/scripts/bootstrap.sh [up|down]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose=(docker compose -f "$here/../docker-compose.stack.yml" --env-file "$here/../e2e.env")

case "${1:-up}" in
  up)
    "${compose[@]}" up -d --build --wait
    echo "栈已就绪：Admin Center http://127.0.0.1:3000（UAM 只在栈内网络可达）"
    ;;
  down)
    "${compose[@]}" down -v
    ;;
  *)
    echo "用法：$0 [up|down]" >&2
    exit 2
    ;;
esac
