#!/usr/bin/env bash
# quickstart S13：无会话粘滞与多实例一致（FR-037 ~ FR-040，SC-010、SC-013）。
# 在 infra/local/docker-compose.scale.yml 的多实例栈上验证（3 个 Admin Center + 3 个 UAM，nginx 无粘滞轮询）。
#
# 用法：infra/local/scripts/scale-checks.sh [up|check|down]
#   up     构建并启动多实例栈，建首个用户（admin）
#   check  默认；栈未启动则先 up，跑完全部断言，打印通过 / 失败条数，任一失败则退出码 1
#   down   停止并删除栈（含卷）
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scale-lib.sh
source "$here/scale-lib.sh"

pass=0
fail=0
assert() { # <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1))
    printf '  PASS  %s\n' "$1"
  else
    fail=$((fail + 1))
    printf '  FAIL  %s（实际：%s，期望：%s）\n' "$1" "$2" "$3"
  fi
}

# 对一个入口连续访问 n 次受保护页面，输出各状态词的出现次数（如 "ok=30"）
sweep() { # <base> <sid> <path> <n>
  local i s tally=""
  for i in $(seq "$4"); do
    s="$(page_state "$1" "$2" "$3")"
    tally="$tally$s
"
  done
  printf '%s' "$tally" | sed '/^$/d' | sort | uniq -c | awk '{printf "%s=%s ", $2, $1}' | sed 's/ $//'
}

# 访问若干（路径, 次数）组合，输出 "ok=<正常次数>"
count_ok() { # <base> <sid> <path> <n> [<path> <n> ...]
  local base="$1" sid="$2" n=0 i
  shift 2
  while [ "$#" -ge 2 ]; do
    for i in $(seq "$2"); do
      [ "$(page_state "$base" "$sid" "$1")" = "ok" ] && n=$((n + 1))
    done
    shift 2
  done
  echo "ok=$n"
}

case "${1:-check}" in
  up) scale_up; exit 0 ;;
  down) scale_down; exit 0 ;;
  check) ;;
  *) echo "用法：$0 [up|check|down]" >&2; exit 2 ;;
esac

stack_is_up || scale_up
for svc in uam-1 uam-2 uam-3 admin-center-1 admin-center-2 admin-center-3 uam-lb nginx; do
  wait_healthy "$svc" 120
done

run_id="$(date +%s)"
new_account="scale$run_id"
new_password="Scale-Check-Pass-1"
log "S13 开始（run_id=$run_id）"

echo "== 1. 登录一次后，请求分到全部 Admin Center 与 UAM 实例，100% 已登录（SC-010）"
sid="$(web_login "$EDGE" "$ADMIN_ACCOUNT" "$ADMIN_PASSWORD")" || { echo "管理员登录失败"; exit 1; }
nginx_mark="$(log_mark nginx)"
uamlb_mark="$(log_mark uam-lb)"
verify_before="$(for i in 1 2 3; do uam_verify_count "uam-$i"; done | tr '\n' ' ')"

assert "经入口（轮询）连续 30 次访问 /users 全部已登录" "$(sweep "$EDGE" "$sid" /users 30)" "ok=30"
for i in 1 2 3; do
  assert "钉在 Admin Center 第 $i 个实例：访问 /users、/sessions 各 5 次全部已登录" \
    "$(count_ok "$(pin_url "$i")" "$sid" /users 5 /sessions 5)" "ok=10"
done

# 入口落点：同一个 cookie、同一个来源，应被严格轮询到 3 个实例（没有粘滞）
counts="$(upstream_counts nginx "$nginx_mark")"
for i in 1 2 3; do
  ip="$(container_ip "admin-center-$i" edge)"
  n="$(printf '%s\n' "$counts" | awk -v t="$ip:3000" '$2 == t {s += $1} END {print s + 0}')"
  # 入口 30 次轮询各 10 次；钉住的 10 次也落在各自实例上，所以每个实例至少 10 次
  if [ "$n" -ge 10 ]; then
    assert "入口把请求分给了 admin-center-$i（$ip，共 $n 次）" "yes" "yes"
  else
    assert "入口把请求分给了 admin-center-$i（$ip，共 $n 次）" "no" "yes"
  fi
done
edge_share="$(printf '%s\n' "$counts" | wc -l | tr -d ' ')"
assert "上游地址种类 = Admin Center 实例数" "$edge_share" "3"

# 这些页面每次渲染都会对 UAM 做 verify；uam-lb 把它们分到 3 个 UAM 实例
verify_after="$(for i in 1 2 3; do uam_verify_count "uam-$i"; done | tr '\n' ' ')"
all_uam_used=yes
read -r b1 b2 b3 <<<"$verify_before"
read -r a1 a2 a3 <<<"$verify_after"
[ "$a1" -gt "$b1" ] && [ "$a2" -gt "$b2" ] && [ "$a3" -gt "$b3" ] || all_uam_used=no
assert "3 个 UAM 实例都处理了 verify 请求（前 $verify_before→后 $verify_after）" "$all_uam_used" "yes"
echo "  uam-lb 上游分布（自 mark 起）："
upstream_counts uam-lb "$uamlb_mark" | sed 's/^/    /'

echo "== 2. 经一个实例新建用户，其他实例立即看到，并可在另一个实例登录（SC-010）"
# /users/new 表单：取 Next 隐藏字段与 idempotencyKey，同源 Origin 提交到实例 1
form_page="$(curl -sS -H "Cookie: asid=$sid" "$(pin_url 1)/users/new")"
form_args=()
while IFS= read -r line; do
  name="$(printf '%s' "$line" | sed -E 's/.*name="([^"]*)".*/\1/')"
  value="$(printf '%s' "$line" | sed -nE 's/.*value="([^"]*)".*/\1/p' | sed 's/&quot;/"/g')"
  form_args+=(-F "$name=$value")
done < <(printf '%s' "$form_page" | grep -o '<input type="hidden"[^>]*>')
create_head="$(curl -sS -D - -o /dev/null -H "Cookie: asid=$sid" -H "Origin: $(pin_url 1)" "${form_args[@]}" \
  -F "userName=扩缩容检查$run_id" -F "accountName=$new_account" -F "password=$new_password" "$(pin_url 1)/users/new" | tr -d '\r')"
create_status="$(printf '%s' "$create_head" | head -n1 | awk '{print $2}')"
new_user_path="$(printf '%s' "$create_head" | sed -nE 's/^[Ll]ocation: (\/users\/[0-9a-f-]+).*/\1/p' | head -n1)"
assert "经 Admin Center 实例 1 新建用户：303 跳到用户详情" "$create_status ${new_user_path:+detail}" "303 detail"
for i in 2 3; do
  found="$(curl -sS -H "Cookie: asid=$sid" "$(pin_url "$i")/users?q=$new_account" | grep -c "扩缩容检查$run_id" || true)"
  assert "Admin Center 实例 $i 的用户列表立即看到新用户" "$([ "$found" -ge 1 ] && echo yes || echo no)" "yes"
  assert "Admin Center 实例 $i 能打开新用户详情" "$(page_state "$(pin_url "$i")" "$sid" "$new_user_path")" "ok"
done
new_sid="$(web_login "$(pin_url 3)" "$new_account" "$new_password")" || new_sid=""
assert "新用户可在 Admin Center 实例 3 登录" "$([ -n "$new_sid" ] && echo yes || echo no)" "yes"
for i in 1 2 3; do
  assert "新用户的会话在实例 $i 上已登录" "$(page_state "$(pin_url "$i")" "$new_sid" /users)" "ok"
done

echo "== 3. 经一个实例退出，其他实例的下一次请求立即视为未登录（FR-038、SC-013）"
logout_status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "Cookie: asid=$new_sid" -H "Origin: $(pin_url 2)" "$(pin_url 2)/logout")"
assert "经 Admin Center 实例 2 退出：303" "$logout_status" "303"
for i in 1 2 3; do
  assert "退出后实例 $i 的下一次请求视为未登录" "$(page_state "$(pin_url "$i")" "$new_sid" /users)" "loggedout"
done

echo "== 4. 强制下线（UAM DELETE /v1/sessions/{ref}）后，任一 Admin Center 实例的下一次请求都视为未登录"
target_sid="$(web_login "$(pin_url 1)" "$new_account" "$new_password")"
for i in 1 2 3; do
  assert "新会话在实例 $i 上已登录" "$(page_state "$(pin_url "$i")" "$target_sid" /users)" "ok"
done
# 操作人 = 管理员自己的会话（经 UAM verify 取得 userId / accountId / sessionRef，与 Admin Center 的做法一致）
operator_json="$(uam_api POST /v1/auth/sessions/verify "{\"sessionId\":\"$sid\"}" |
  sed -n '2p' | sed -E 's/.*"user":\{"id":"([^"]*)".*"account":\{"id":"([^"]*)".*"sessionRef":"([^"]*)".*/{"userId":"\1","accountId":"\2","sessionRef":"\3"}/')"
target_ref="$(uam_api POST /v1/auth/sessions/verify "{\"sessionId\":\"$target_sid\"}" | sed -n '2p' | sed -E 's/.*"sessionRef":"([^"]*)".*/\1/')"
terminate_status="$(uam_api DELETE "/v1/sessions/$target_ref" "" "$operator_json" | head -n1)"
assert "强制下线：UAM 返回 204" "$terminate_status" "204"
for i in 1 2 3; do
  assert "强制下线后实例 $i 的下一次请求视为未登录" "$(page_state "$(pin_url "$i")" "$target_sid" /users)" "loggedout"
done
assert "管理员自己的会话不受影响（实例 1）" "$(page_state "$(pin_url 1)" "$sid" /users)" "ok"

echo "== 5. 终端来源地址不可伪造：客户端自带的 X-Forwarded-For 不能绕过登录失败限制（TRUSTED_PROXY_HOPS=1）"
probe="throttle-probe-$run_id"
last=""
for i in 1 2 3 4 5 6; do
  # 每次都带一个不同的、伪造的最左项；右数第 1 项始终是 nginx 追加的真实对端地址
  last="$(web_login "$EDGE" "$probe" "Wrong-Password-$i" "198.51.100.$i" 2>&1 || true)"
done
assert "连续 5 次失败后第 6 次被限流（伪造的 X-Forwarded-For 无效）" "$(printf '%s' "$last" | grep -c '尝试过于频繁' || true)" "1"

echo
echo "S13 结果：通过 $pass 项，失败 $fail 项"
[ "$fail" -eq 0 ]
