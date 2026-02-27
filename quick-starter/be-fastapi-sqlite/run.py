#!/usr/bin/env python3
"""
Kids Homework Review - Backend Server

使用 uv 运行（推荐）:
    uv run python run.py          # 默认配置启动
    uv run python run.py --reload # 开发模式（自动重载）
    uv run python run.py --port 8000  # 指定端口

或激活虚拟环境后运行:
    source .venv/bin/activate  # macOS/Linux
    .venv\Scripts\activate     # Windows
    python run.py --reload
"""

import argparse
import sys
from pathlib import Path

import uvicorn


def main():
    parser = argparse.ArgumentParser(description="Kids Homework Review Backend Server")
    parser.add_argument(
        "--host",
        type=str,
        default="127.0.0.1",
        help="Bind socket to this host (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8000,
        help="Bind socket to this port (default: 8000)",
    )
    parser.add_argument(
        "--reload",
        action="store_true",
        help="Enable auto-reload (useful for development)",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=1,
        help="Number of worker processes (default: 1)",
    )
    parser.add_argument(
        "--log-level",
        type=str,
        default="info",
        choices=["critical", "error", "warning", "info", "debug"],
        help="Log level (default: info)",
    )

    args = parser.parse_args()

    # 确保在 backend 目录下运行
    backend_dir = Path(__file__).parent
    sys.path.insert(0, str(backend_dir / "src"))

    print("=" * 60)
    print("🚀 Kids Homework Review - Backend Server")
    print("=" * 60)
    print(f"   Host: {args.host}")
    print(f"   Port: {args.port}")
    print(f"   Reload: {args.reload}")
    print(f"   Workers: {args.workers}")
    print(f"   Log Level: {args.log_level}")
    print("=" * 60)

    uvicorn.run(
        "main:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
        workers=args.workers,
        log_level=args.log_level,
    )


if __name__ == "__main__":
    main()
