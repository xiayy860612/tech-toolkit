#!/usr/bin/env bash
# 服务密钥脚本的公共函数（被 bootstrap.sh 与 rotate-key.sh 以 source 方式引用，不单独执行）。
# 兼容 macOS 自带的 bash 3.2：不用关联数组、mapfile。
#
# 调用方必须先定义函数 uam_compose：它把参数交给 docker compose（带上调用方选定的编排文件与 --env-file）。
# 本文件不含任何密钥；生成的密钥只写进调用方给的文件（权限 0600），从不打印。

lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC2034  # 由 source 本文件的脚本使用
local_dir="$(cd "$lib_dir/.." && pwd)"

key_log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# 字符串的 sha256 十六进制（macOS 用 shasum，Linux 用 sha256sum）
sha256_hex() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1
  else
    printf '%s' "$1" | sha256sum | cut -d' ' -f1
  fi
}

# 用 UAM 的 generate-service-key 生成密钥：不连库、不连 Redis（--no-deps，镜像里不需要数据库配置）。
# 标准输出（只有密钥）写进 <密钥文件>（0600）；标准错误里的哈希与本地独立计算的 sha256 核对一致才算成功。
# 用法：generate_service_key <客户端名> <密钥文件>；成功后设置全局变量 GENERATED_HASH（sha256:<hex>）。
generate_service_key() {
  local client="$1" key_file="$2" err_file key cli_hash own_hash
  err_file="$(mktemp)"
  : >"$key_file"
  chmod 600 "$key_file"
  if ! uam_compose run --rm -T --no-deps "${UAM_KEY_SERVICE:-uam}" \
    java -jar /app/uam.jar generate-service-key --client "$client" >"$key_file" 2>"$err_file"; then
    echo "generate-service-key 失败（标准错误如下，不含密钥）：" >&2
    cat "$err_file" >&2
    rm -f "$err_file"
    return 1
  fi
  key="$(tr -d '\n' <"$key_file")"
  if ! printf '%s' "$key" | grep -Eq '^uamk_[A-Za-z0-9_-]{43}$'; then
    echo "generate-service-key 的标准输出不是预期的密钥格式（不打印内容）" >&2
    rm -f "$err_file"
    return 1
  fi
  cli_hash="$(grep -Eo 'sha256:[0-9a-f]{64}' "$err_file" | head -n1 || true)"
  own_hash="sha256:$(sha256_hex "$key")"
  rm -f "$err_file"
  if [ "$cli_hash" != "$own_hash" ]; then
    echo "generate-service-key 报告的哈希与本地计算的 sha256 不一致" >&2
    return 1
  fi
  # shellcheck disable=SC2034  # 由调用方读取
  GENERATED_HASH="$own_hash"
}

# 一个客户端的 UAM_CLIENTS（JSON）：clients_json <客户端名> <哈希>...（1 或 2 个，轮换期间两个）
clients_json() {
  local client="$1" hashes="" hash
  shift
  for hash in "$@"; do
    hashes="${hashes:+$hashes,}\"$hash\""
  done
  printf '[{"id":"%s","keyHashes":[%s],"permissions":["assert-end-user-context"]}]' "$client" "$hashes"
}

# 写 compose 的 --env-file 覆盖文件（0600）：write_env_override <文件> <UAM_SERVICE_KEY> <UAM_CLIENTS>
# 与 e2e.env 同一写法（UAM_CLIENTS 用单引号）；后一个 --env-file 覆盖前一个。
write_env_override() {
  local file="$1"
  : >"$file"
  chmod 600 "$file"
  printf "UAM_SERVICE_KEY=%s\nUAM_CLIENTS='%s'\n" "$2" "$3" >"$file"
}
