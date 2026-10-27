#!/usr/bin/env bash
# quickstart S14：持续访问下依次重启全部实例（3 个 UAM、3 个 Admin Center），统计意外登出数与失败请求数（FR-039、SC-012）。
#
# 用法：devops/local/scripts/rolling-restart.sh
# 环境变量（均有默认值）：
#   ROLL_SESSIONS     同时在线的会话数（管理员 + 额外用户），默认 5
#   ROLL_WORKERS      并发访问的后台 worker 数，默认 4
#   ROLL_SETTLE       每个实例重启后、继续下一个前的等待秒数，默认 5
#   ROLL_MAX_FAILED   若设置，失败请求数超过它则退出码为 1（默认不作为判定条件，只如实报告）
#
# 判定：
#   意外登出 = 持续访问期间，任一已登录会话收到「跳到 /login 或 /session/expire」的响应。必须为 0。
#   重启结束后，重启前有效的全部会话在每个 Admin Center 实例上依然有效。
#   失败请求 = 非 200（含 502 / 503）、连接失败或超时，或页面提示「服务暂不可用」（UAM 暂时不可达，用户没被登出但没拿到数据）。
#   两者分开统计：登出是 SC-012 的判定；失败请求数如实报告，不掩盖。
# 退出码：0 = 0 次意外登出且重启后会话全部有效（且满足可选的 ROLL_MAX_FAILED）；1 = 否；2 = 用法 / 环境错误。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scale-lib.sh
source "$here/scale-lib.sh"

sessions="${ROLL_SESSIONS:-5}"
workers="${ROLL_WORKERS:-4}"
settle="${ROLL_SETTLE:-5}"
extra_password="Roll-Check-Pass-1"
work="$(mktemp -d)"
stop_file="$work/stop"
worker_pids=()

# shellcheck disable=SC2329  # 经 trap 调用
cleanup() {
  touch "$stop_file"
  local pid
  for pid in ${worker_pids[@]+"${worker_pids[@]}"}; do
    kill "$pid" 2>/dev/null || true
  done
  rm -rf "$work"
}
trap cleanup EXIT

stack_is_up || { echo "多实例栈未启动：先运行 devops/local/scripts/scale-checks.sh up" >&2; exit 2; }
for svc in uam-1 uam-2 uam-3 admin-center-1 admin-center-2 admin-center-3 uam-lb nginx; do
  wait_healthy "$svc" 120
done

# ---- 1. 准备：N 个同时有效的会话（管理员 + N-1 个额外用户）----
log "准备 $sessions 个同时有效的会话"
admin_sid="$(web_login "$EDGE" "$ADMIN_ACCOUNT" "$ADMIN_PASSWORD")" || { echo "管理员登录失败" >&2; exit 2; }
operator_json="$(uam_api POST /v1/auth/sessions/verify "{\"sessionId\":\"$admin_sid\"}" |
  sed -n '2p' | sed -E 's/.*"user":\{"id":"([^"]*)".*"account":\{"id":"([^"]*)".*"sessionRef":"([^"]*)".*/{"userId":"\1","accountId":"\2","sessionRef":"\3"}/')"
sids=("$admin_sid")
i=1
while [ "$i" -lt "$sessions" ]; do
  # 已存在时 UAM 返回 409，忽略；随后一律用同一个测试密码登录
  uam_api POST /v1/users "{\"userName\":\"滚动重启$i\",\"accountName\":\"roll.user$i\",\"password\":\"$extra_password\"}" "$operator_json" "roll-user-$i-idem-key" >/dev/null 2>&1 || true
  sid="$(web_login "$EDGE" "roll.user$i" "$extra_password")" || { echo "额外用户 roll.user$i 登录失败" >&2; exit 2; }
  sids+=("$sid")
  i=$((i + 1))
done
for sid in "${sids[@]}"; do
  [ "$(page_state "$EDGE" "$sid" /users)" = "ok" ] || { echo "准备阶段会话不可用" >&2; exit 2; }
done

# ---- 2. 持续访问：每个 worker 循环访问各会话的几个页面，逐条记录「时间 状态词 路径」----
paths=(/users /sessions "/users?q=roll")
worker() { # <编号>
  local id="$1" n=0 sid path
  while [ ! -f "$stop_file" ]; do
    sid="${sids[$(((n + id) % ${#sids[@]}))]}"
    path="${paths[$(((n + id) % ${#paths[@]}))]}"
    printf '%s %s %s\n' "$(date +%s)" "$(page_state "$EDGE" "$sid" "$path")" "$path" >>"$work/w$id.log"
    n=$((n + 1))
  done
}
log "启动 $workers 个访问 worker，经入口 $EDGE 持续访问"
w=1
while [ "$w" -le "$workers" ]; do
  worker "$w" &
  worker_pids+=("$!")
  w=$((w + 1))
done
sleep 5 # 基线：重启前先跑几秒

# ---- 3. 依次重启全部实例 ----
restart_log="$work/restarts.log"
for svc in uam-1 uam-2 uam-3 admin-center-1 admin-center-2 admin-center-3; do
  started="$(date +%s)"
  log "重启 $svc"
  compose restart "$svc" >/dev/null 2>&1
  wait_healthy "$svc" 120
  ended="$(date +%s)"
  elapsed=$((ended - started))
  printf '%s %ss（时刻 %s ~ %s，便于与失败请求的时刻对照）\n' "$svc" "$elapsed" "$started" "$ended" | tee -a "$restart_log"
  # 停机耗时接近 stop_grace_period（UAM 40s / Admin Center 20s）说明优雅停机没有自行完成、被 SIGKILL 了
  if [ "$elapsed" -ge 15 ]; then
    echo "  警告：$svc 重启耗时 ${elapsed}s，疑似被 SIGKILL（在途请求可能被截断）" | tee -a "$restart_log"
  fi
  sleep "$settle"
done
sleep 3

touch "$stop_file"
for pid in "${worker_pids[@]}"; do wait "$pid" 2>/dev/null || true; done
worker_pids=()

# ---- 4. 重启后：重启前有效的会话在每个 Admin Center 实例上依然有效 ----
post_bad=0
for n in 1 2 3; do
  for sid in "${sids[@]}"; do
    state="$(page_state "$(pin_url "$n")" "$sid" /users)"
    if [ "$state" != "ok" ]; then
      post_bad=$((post_bad + 1))
      log "重启后会话在 Admin Center 实例 $n 上状态为 $state"
    fi
  done
done

# ---- 5. 统计 ----
cat "$work"/w*.log >"$work/all.log"
total="$(wc -l <"$work/all.log" | tr -d ' ')"
count_state() { awk -v s="$1" '$2 == s {n++} END {print n + 0}' "$work/all.log"; }
ok="$(count_state ok)"
loggedout="$(count_state loggedout)"
unavailable="$(count_state unavailable)"
curl_error="$(count_state curl-error)"
http_bad="$(awk '$2 ~ /^http-/ {n++} END {print n + 0}' "$work/all.log")"
failed=$((unavailable + curl_error + http_bad))

echo
echo "== S14 滚动重启结果 =="
echo "重启顺序与耗时（含等待就绪）："
sed 's/^/  /' "$restart_log"
echo "持续访问请求总数：$total（会话 $sessions 个，worker $workers 个）"
echo "  正常（ok）：$ok"
echo "  意外登出（跳到 /login 或 /session/expire）：$loggedout"
echo "  失败请求：$failed（页面提示服务暂不可用 $unavailable，连接失败或超时 $curl_error，非 200/非登出跳转 $http_bad）"
if [ "$failed" -gt 0 ]; then
  echo "  失败请求按（状态, 路径）与发生时刻："
  awk '$2 != "ok" && $2 != "loggedout" {print "    " $1, $2, $3}' "$work/all.log" | sort | uniq -c | head -20
fi
echo "重启后会话仍有效：$((3 * sessions - post_bad)) / $((3 * sessions))（$sessions 个会话 × 3 个 Admin Center 实例）"

verdict=0
[ "$loggedout" -eq 0 ] || verdict=1
[ "$post_bad" -eq 0 ] || verdict=1
if [ -n "${ROLL_MAX_FAILED:-}" ] && [ "$failed" -gt "$ROLL_MAX_FAILED" ]; then
  echo "失败请求 $failed 超过 ROLL_MAX_FAILED=$ROLL_MAX_FAILED"
  verdict=1
fi
if [ "$verdict" -eq 0 ]; then
  echo "结果：通过（0 次意外登出，重启后会话全部有效；失败请求 $failed 个，见上）"
else
  echo "结果：失败"
fi
exit "$verdict"
