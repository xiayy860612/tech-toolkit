#!/usr/bin/env bash
# 多实例栈脚本的公共函数（被 scale-checks.sh 与 rolling-restart.sh 以 source 方式引用，不单独执行）。
# 兼容 macOS 自带的 bash 3.2：不用关联数组、mapfile、wait -n。
# 配置来自 devops/local/e2e.env（仅限本地的固定测试值）；除此之外不含任何密钥。

lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
local_dir="$(cd "$lib_dir/.." && pwd)"

EDGE="${SCALE_EDGE:-http://127.0.0.1:8088}"                 # 入口（轮询）
PIN_BASE_PORT="${SCALE_PIN_BASE_PORT:-8090}"                # 8091 ~ 8093 把请求钉在第 1 ~ 3 个 Admin Center 上
PROJECT="uam-scale"
ADMIN_ACCOUNT="${E2E_ADMIN_ACCOUNT:-admin}"
ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD:-E2e-Admin-Pass-1}"
ADMIN_NAME="${E2E_ADMIN_NAME:-系统管理员}"

# e2e.env 里的 UAM_SERVICE_KEY 仅用于下面读取 UAM 的 /actuator/prometheus（本地测试值）
UAM_SERVICE_KEY="$(sed -n 's/^UAM_SERVICE_KEY=//p' "$local_dir/e2e.env")"

compose() {
  # 显式 -p：COMPOSE_PROJECT_NAME 会盖过编排文件的 name，scale_down 的 `down -v` 可能误删别的项目
  docker compose -p "$PROJECT" -f "$local_dir/docker-compose.scale.yml" --env-file "$local_dir/e2e.env" "$@"
}

pin_url() { printf 'http://127.0.0.1:%s' "$((PIN_BASE_PORT + $1))"; }

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# ---- 栈的启停 ----

# 首个用户（init-admin 仅在没有任何用户时创建，幂等）；密码只经标准输入传给部署期命令
scale_init_admin() {
  printf '%s\n' "$ADMIN_PASSWORD" | compose run --rm -T -e SPRING_PROFILES_ACTIVE=cli uam-1 \
    java -jar /app/uam.jar init-admin --account-name "$ADMIN_ACCOUNT" --user-name "$ADMIN_NAME" >/dev/null 2>&1 || true
}

scale_up() {
  compose up -d --build --wait
  scale_init_admin
  log "多实例栈已就绪：入口 $EDGE（轮询）；钉住实例 $(pin_url 1) $(pin_url 2) $(pin_url 3)；UAM 不对宿主机开放"
}

scale_down() {
  compose --profile load down -v --remove-orphans
}

stack_is_up() {
  [ "$(compose ps --status running -q 2>/dev/null | wc -l | tr -d ' ')" -ge 9 ]
}

container_health() { # <服务名> -> healthy / unhealthy / starting / none
  local id
  id="$(compose ps -q "$1" 2>/dev/null | head -n1)"
  [ -n "$id" ] || { echo none; return; }
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo none
}

wait_healthy() { # <服务名> [超时秒]
  local svc="$1" timeout="${2:-120}" waited=0
  while [ "$(container_health "$svc")" != "healthy" ]; do
    sleep 1
    waited=$((waited + 1))
    if [ "$waited" -ge "$timeout" ]; then
      log "等待 $svc 就绪超时（${timeout}s）"
      return 1
    fi
  done
}

# ---- Admin Center（经 HTTP，cookie 以请求头显式携带）----

# 以表单方式登录（与浏览器无 JS 时的提交完全一致）：取登录页里 Next 的隐藏字段，同源 Origin，多段表单提交。
# 成功时在标准输出打印会话标识；失败时打印页面里的错误文案到标准错误并返回 1。
# 第三个参数可给一个伪造的 X-Forwarded-For（用来验证它不会被采信）。
web_login() { # <base> <account> <password> [spoofed-xff]
  local base="$1" account="$2" password="$3" xff="${4:-}" page line name value
  local args=() hdrs=() out status
  page="$(curl -sS --max-time 15 "$base/login")" || return 1
  while IFS= read -r line; do
    name="$(printf '%s' "$line" | sed -E 's/.*name="([^"]*)".*/\1/')"
    value="$(printf '%s' "$line" | sed -nE 's/.*value="([^"]*)".*/\1/p' | sed 's/&quot;/"/g')"
    args+=(-F "$name=$value")
  done < <(printf '%s' "$page" | grep -o '<input type="hidden"[^>]*>')
  hdrs=(-H "Origin: $base")
  [ -n "$xff" ] && hdrs+=(-H "X-Forwarded-For: $xff")
  out="$(curl -sS --max-time 30 -D - "${hdrs[@]}" "${args[@]}" -F "accountName=$account" -F "password=$password" "$base/login")" || return 1
  status="$(printf '%s' "$out" | head -n1 | awk '{print $2}')"
  if [ "$status" = "303" ]; then
    printf '%s' "$out" | tr -d '\r' | sed -nE 's/^[Ss]et-[Cc]ookie: asid=([^;]*).*/\1/p' | head -n1
    return 0
  fi
  # 200：表单带着错误文案重新渲染；把常见提示摘出来（不含任何会话标识）
  printf '%s' "$out" | grep -o '尝试过于频繁[^<]*\|账号名或密码错误\|服务暂不可用[^<]*\|请求来源校验失败[^<]*' | head -n1 >&2 || true
  return 1
}

# 带会话 cookie 访问一个页面，输出一个状态词：
#   ok            200 且是正常内容
#   unavailable   200 但页面提示「服务暂不可用」（UAM 暂时不可达；用户未被登出，但这个请求没有拿到数据）
#   loggedout     3xx 跳到 /login 或 /session/expire（用户被当作未登录）
#   http-NNN      其他状态码（含 502 / 503 等失败）
#   curl-error    连接失败 / 超时
page_state() { # <base> <sid> <path>
  local base="$1" sid="$2" path="$3" body_file code location rc
  body_file="$(mktemp)"
  code="$(curl -sS --max-time 20 -o "$body_file" -w '%{http_code} %{redirect_url}' -H "Cookie: asid=$sid" "$base$path" 2>/dev/null)"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    rm -f "$body_file"
    echo curl-error
    return 0
  fi
  location="${code#* }"
  code="${code%% *}"
  case "$code" in
    200)
      if grep -q '服务暂不可用' "$body_file"; then echo unavailable; else echo ok; fi
      ;;
    301 | 302 | 303 | 307 | 308)
      case "$location" in
        */login* | */session/expire*) echo loggedout ;;
        *) echo "http-$code" ;;
      esac
      ;;
    *) echo "http-$code" ;;
  esac
  rm -f "$body_file"
}

# ---- 直接调 UAM（UAM 不对宿主机开放：借 admin-center-1 容器里的 node 与它自己的 UAM_BASE_URL / UAM_SERVICE_KEY 调 uam-lb）----
# 输出：第一行 HTTP 状态码，其后是响应体。第 4 个参数是操作人 JSON（写操作用），第 5 个是 Idempotency-Key（创建用户用）。
uam_api() { # <METHOD> <PATH> [json-body] [operator-json] [idempotency-key]
  compose exec -T admin-center-1 node -e '
    const [method, path, body, operator, idem] = process.argv.slice(1);
    const headers = { Authorization: "Bearer " + process.env.UAM_SERVICE_KEY, Accept: "application/json" };
    if (body) headers["Content-Type"] = "application/json";
    if (idem) headers["Idempotency-Key"] = idem;
    if (operator) {
      const o = JSON.parse(operator);
      headers["X-Operator-User-Id"] = o.userId;
      headers["X-Operator-Account-Id"] = o.accountId;
      headers["X-Operator-Session-Ref"] = o.sessionRef;
    }
    fetch(process.env.UAM_BASE_URL + path, { method, headers, body: body || undefined })
      .then(async (r) => { console.log(r.status); console.log(await r.text()); })
      .catch((e) => { console.log("0"); console.log(String(e)); });
  ' "$1" "$2" "${3:-}" "${4:-}" "${5:-}"
}

# ---- 统计：nginx 日志里请求落到了哪些上游 ----
log_mark() { # <服务名> -> 当前日志行数
  compose logs --no-log-prefix "$1" 2>/dev/null | wc -l | tr -d ' '
}

# 自 mark 之后，各上游地址收到的请求数（每行一个 "次数 地址"）；重试的请求只计最终的地址
upstream_counts() { # <服务名> <mark>
  compose logs --no-log-prefix "$1" 2>/dev/null | tail -n +"$(($2 + 1))" |
    sed -nE 's/.* upstream=([^ ]+) .*/\1/p' | awk -F', ' '{print $NF}' | sort | uniq -c
}

container_ip() { # <服务名> <网络名后缀>
  local id
  id="$(compose ps -q "$1" | head -n1)"
  docker inspect -f "{{(index .NetworkSettings.Networks \"${PROJECT}_$2\").IPAddress}}" "$id"
}

# 某个 UAM 实例处理过的 verify 请求累计数（读该实例自己的 Prometheus 指标）
uam_verify_count() { # <uam 服务名>
  # shellcheck disable=SC2016  # 单引号里的 $UAM_KEY 由容器内的 bash 展开，这正是本意
  compose exec -T -e UAM_KEY="$UAM_SERVICE_KEY" "$1" bash -c '
    exec 3<>/dev/tcp/127.0.0.1/8080
    printf "GET /actuator/prometheus HTTP/1.0\r\nAuthorization: Bearer %s\r\n\r\n" "$UAM_KEY" >&3
    cat <&3
  ' 2>/dev/null | awk '/^http_server_requests_seconds_count/ && /uri="\/v1\/auth\/sessions\/verify"/ {s += $NF} END {printf "%d\n", s}'
}
