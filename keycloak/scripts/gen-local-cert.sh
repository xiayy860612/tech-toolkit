#!/usr/bin/env bash
# 生成本地验证用自签名证书（仅本地；生产使用 CA 签发的真实证书）。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p certs
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout certs/tls.key -out certs/tls.crt \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
chmod 600 certs/tls.key
echo "generated certs/tls.crt and certs/tls.key"
