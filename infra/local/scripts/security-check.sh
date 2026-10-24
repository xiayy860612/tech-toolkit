#!/usr/bin/env bash
# 安全检查（T185；FR-013、FR-033、SC-014、SC-016）：静态检查 + 对运行中 E2E 栈的运行期检查。
# 兼容 macOS 自带的 bash 3.2：不用关联数组、mapfile。除 e2e.env 里公开标注为「仅限测试」的固定密钥外，不含任何密钥。
#
# 用法：infra/local/scripts/security-check.sh [static|runtime|all]   （默认 all）
#   static   不需要 Docker：
#            S1 被跟踪的 .env 文件        S2 Admin Center 无数据库驱动 / ORM（FR-013）
#            S3 仓库与历史里的密钥特征    S4 src/lib 服务端模块都有 import "server-only"
#            S5 浏览器包（构建 .next/static）里没有服务密钥
#            S6 写入口（Server Action 与 POST 路由）先做同源校验，并跑相关单元测试
#   runtime  需要已启动的 E2E 栈（infra/local/scripts/bootstrap.sh up），否则退出码 2：
#            用 curl 走一遍真实流程（登录、浏览、新建、强制下线、停用 / 启用、改密、退出），
#            R1 ~ R8 跨站伪造矩阵：login、createUser、terminateSession、disableUser、enableUser、changePassword、POST /logout
#               每个写入口都用「外站 Origin / Sec-Fetch-Site: cross-site / 两者都没有」各打一次，必须被拒且状态不变；
#               同源请求作为对照必须成功（证明脚本真的驱动了这个入口）
#            R9 响应与日志里没有密码、哈希、会话标识、服务密钥（SC-014）；运行中栈提供的浏览器包里没有服务密钥
#   all      先 static，再 runtime；栈原本没启动则自己 up，结束时 down（原本已启动则保持原样）。
#
# 环境变量（均有默认值）：
#   SECURITY_CHECK_SKIP_BUILD=1   S5 复用已有的 apps/admin-center/.next（不重新构建；输出会注明）
#   SECURITY_CHECK_BASE           Admin Center 地址，默认 http://127.0.0.1:3000
#   E2E_ADMIN_ACCOUNT / E2E_ADMIN_PASSWORD   与 bootstrap.sh 一致的首个用户（仅限本地 E2E 的假密码）
# 退出码：0 全部通过；1 有失败项；2 用法 / 环境错误（如 runtime 但栈没起来）。
#
# 密钥扫描的允许名单（保持最小，每项一句理由；其余路径出现完整长度的 uamk_ 密钥一律失败）：
#   infra/local/e2e.env —— 本地 E2E 的固定测试密钥，文件头已注明「仅限本地 E2E」；脚本还会核对该文件里只有这一把、且没有私钥特征。
#   （UAM 测试里的假密钥都不是完整长度，或在运行时拼出，无需允许。）
#   历史扫描另有两处遗留占位 PEM 的路径允许（只放行私钥特征，不放行 uamk_ 密钥），理由写在 LEGACY_PEM_ALLOW 旁。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
app="$repo/apps/admin-center"
# 复用 scale-lib.sh 的 log、page_state、E2E 的账号与固定测试密钥（只读 e2e.env）
# shellcheck source=scale-lib.sh
source "$here/scale-lib.sh"

mode="${1:-all}"
case "$mode" in static | runtime | all) ;; *)
  echo "用法：$0 [static|runtime|all]" >&2
  exit 2
  ;;
esac

BASE="${SECURITY_CHECK_BASE:-http://127.0.0.1:3000}"
STACK_FILE="$local_dir/docker-compose.stack.yml"
E2E_ENV="$local_dir/e2e.env"
COOKIE_NAME="$(sed -n 's/^SESSION_COOKIE_NAME=//p' "$E2E_ENV")"
COOKIE_NAME="${COOKIE_NAME:-asid}"
stack_compose() { docker compose -f "$STACK_FILE" --env-file "$E2E_ENV" "$@"; }

work="$(mktemp -d)"
mkdir -p "$work/http" "$work/static"
started_stack=0
# shellcheck disable=SC2329  # 经 trap 调用
cleanup() {
  local status=$?
  if [ "$started_stack" -eq 1 ]; then
    log "关闭本脚本启动的 E2E 栈"
    "$here/bootstrap.sh" down >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT

pass=0
fail=0
ok() {
  pass=$((pass + 1))
  printf '  PASS  %s\n' "$1"
}
bad() {
  fail=$((fail + 1))
  printf '  FAIL  %s\n' "$1"
}
# check <描述> <命令...>：命令退出码为 0 即通过
check() {
  local desc="$1"
  shift
  if "$@"; then ok "$desc"; else bad "$desc"; fi
}
assert_eq() { # <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（实际：$2，期望：$3）"; fi
}
section() { printf '\n== %s ==\n' "$1"; }

# ============================ 静态检查 ============================

# S1：被跟踪的 .env 文件。允许：*.example，以及 infra/local/e2e.env（本地 E2E 的公开测试配置）
s1_env_files() {
  local offenders
  offenders="$(git -C "$repo" ls-files | awk -F/ '
    { b = $NF }
    (b == ".env" || b ~ /^\.env\./ || b ~ /\.env$/) && b !~ /\.example$/ && $0 != "infra/local/e2e.env"')"
  if [ -n "$offenders" ]; then
    printf '%s\n' "$offenders" | sed 's/^/        被跟踪：/'
    return 1
  fi
}
s1_e2e_env_marked() {
  head -n3 "$E2E_ENV" | grep -q '仅限本地 E2E'
}

# S2：Admin Center 的依赖里没有数据库驱动 / ORM / 缓存客户端（FR-013：不持久化数据）
s2_no_db_deps() {
  node -e '
    const pkg = require(process.argv[1]);
    const deps = Object.keys({ ...pkg.dependencies, ...pkg.devDependencies });
    const bad = /^(pg|pg-.*|postgres|postgres-.*|postgresql|mysql.*|mariadb|mongodb|mongoose|redis|ioredis|@redis\/.*|@prisma\/.*|prisma|typeorm|sequelize|knex|kysely|drizzle-.*|@mikro-orm\/.*|better-sqlite3|sqlite3?|@libsql\/.*|@vercel\/postgres|@neondatabase\/.*|@planetscale\/.*|@supabase\/.*|mssql|tedious|oracledb|cassandra-driver|lowdb|@aws-sdk\/client-dynamodb)$/;
    const hits = deps.filter((d) => bad.test(d));
    if (hits.length) { console.log("        命中：" + hits.join(", ")); process.exit(1); }
  ' "$app/package.json"
}

# S3：完整长度的 uamk_ 密钥（uamk_ + 43 个 base64url 字符）与私钥特征，扫描工作区（含未跟踪、未忽略的文件）与全部历史。
KEY_RE='uamk_[A-Za-z0-9_-]{43}'
PEM_RE='BEGIN [A-Z ]*PRIVATE KEY'
ALLOW_KEY_FILE="infra/local/e2e.env"
s3_worktree() {
  local files
  files="$(git -C "$repo" grep --untracked -lIE -e "$KEY_RE" -e "$PEM_RE" -- . ":(exclude)$ALLOW_KEY_FILE" || true)"
  if [ -n "$files" ]; then
    printf '%s\n' "$files" | sed 's/^/        命中文件（内容不打印）：/'
    return 1
  fi
}
# 历史里的允许名单（只放行私钥特征、且只放行这两个确切路径；密钥 uamk_ 在历史里没有任何例外）：
#   sb3-jdk17/examples/service-example-h2/src/main/resources/jwt-rsa-key.pem
#   quick-starter/be-spring-mvc-h2/src/main/resources/jwt-rsa-key.pem
#     —— 早期遗留示例项目的占位 PEM（正文只有一行 24 个字符，不是有效私钥），只存在于 quick-starter / fix-deploy /
#        sb3-migrate-to-sb4 等旧分支，不在 main 与本特性分支的历史里，工作区也没有；与本特性无关，改写历史由仓库所有者决定。
LEGACY_PEM_ALLOW="
b/sb3-jdk17/examples/service-example-h2/src/main/resources/jwt-rsa-key.pem
b/quick-starter/be-spring-mvc-h2/src/main/resources/jwt-rsa-key.pem
"
# history_hits <正则> [允许的路径（换行分隔）]：输出「提交 路径」（内容永远不打印）；允许名单里的路径不输出
history_hits() {
  { grep -E -e '^(@@commit |\+\+\+ )' -e "^\\+.*($1)" "$work/history.diff" || true; } |
    awk -v allow="$(printf '%s' "${2:-}" | tr '\n' ' ')" '
      BEGIN { n = split(allow, a, " "); for (i = 1; i <= n; i++) if (a[i] != "") ok[a[i]] = 1 }
      /^@@commit /{c=$2; next}
      /^\+\+\+ /{p=$2; next}
      !(p in ok) {print c " " p}' | sort -u
}
s3_history() {
  local hits all_pem unallowed_pem
  git -C "$repo" log --all --no-color -p --format='@@commit %h' -- . ":(exclude)$ALLOW_KEY_FILE" >"$work/history.diff"
  hits="$({ history_hits "$KEY_RE"; history_hits "$PEM_RE" "$LEGACY_PEM_ALLOW"; } | sort -u | sed 's/^/        历史命中（内容不打印）：提交 /')"
  all_pem="$(history_hits "$PEM_RE" | wc -l | tr -d ' ')"
  unallowed_pem="$(history_hits "$PEM_RE" "$LEGACY_PEM_ALLOW" | wc -l | tr -d ' ')"
  echo "        扫描了 $(grep -c '^@@commit ' "$work/history.diff") 个提交；允许名单放行 $((all_pem - unallowed_pem)) 处遗留占位 PEM（理由见脚本）"
  if [ -n "$hits" ]; then
    printf '%s\n' "$hits"
    return 1
  fi
}
# 允许名单本身要守住：只有一把标注过的测试密钥，没有私钥特征
s3_allowlist_minimal() {
  [ "$(grep -c 'uamk_' "$repo/$ALLOW_KEY_FILE")" = "1" ] &&
    grep -q '^UAM_SERVICE_KEY=uamk_e2e_' "$repo/$ALLOW_KEY_FILE" &&
    ! grep -qE "$PEM_RE" "$repo/$ALLOW_KEY_FILE"
}

# S4：src/lib 里的服务端模块都有 import "server-only"。
# 允许名单（客户端组件也会引用或不含运行期服务端逻辑；其余文件一律必须有）：
#   utils.ts            cn()：shadcn/ui 与业务组件（含客户端组件）共用的类名合并
#   format-date.ts      纯日期格式化函数，客户端列表组件也用
#   users-href.ts       纯 URL 构造（用户列表分页链接），客户端组件也用
#   sessions-href.ts    纯 URL 构造（在线用户分页链接），客户端组件也用
#   uam/types.generated.ts   openapi-typescript 生成的类型声明，无运行期代码
SERVER_ONLY_ALLOW="
src/lib/utils.ts
src/lib/format-date.ts
src/lib/users-href.ts
src/lib/sessions-href.ts
src/lib/uam/types.generated.ts
"
s4_server_only() {
  local f rel missing=0 count=0
  while IFS= read -r f; do
    rel="${f#"$app"/}"
    if printf '%s' "$SERVER_ONLY_ALLOW" | grep -qxF "$rel"; then continue; fi
    count=$((count + 1))
    if ! grep -q '^import "server-only";' "$f"; then
      echo "        缺少 import \"server-only\"：$rel"
      missing=1
    fi
  done < <(find "$app/src/lib" \( -name '*.ts' -o -name '*.tsx' \) | sort)
  echo "        检查了 $count 个 src/lib 服务端模块（另有 $(printf '%s' "$SERVER_ONLY_ALLOW" | grep -c .) 个在允许名单里）"
  [ "$missing" -eq 0 ]
}
s4_allowlist_exists() { # 允许名单里的文件必须真实存在（防止名单悬空而掩盖改名）
  local rel miss=0
  for rel in $SERVER_ONLY_ALLOW; do
    [ -f "$app/$rel" ] || {
      echo "        允许名单里的文件不存在：$rel"
      miss=1
    }
  done
  [ "$miss" -eq 0 ]
}

# S5：构建浏览器包（不设运行期环境变量），在 .next/static 里找服务密钥
s5_build() {
  if [ "${SECURITY_CHECK_SKIP_BUILD:-0}" = "1" ]; then
    echo "        SECURITY_CHECK_SKIP_BUILD=1：复用已有 .next（未重新构建）"
    [ -d "$app/.next/static" ]
    return
  fi
  if [ ! -d "$app/node_modules" ]; then
    echo "        缺少 apps/admin-center/node_modules：先运行 npm ci"
    return 1
  fi
  if (cd "$app" && env -u UAM_SERVICE_KEY -u UAM_BASE_URL npm run build >"$work/build.log" 2>&1); then
    return 0
  fi
  echo "        构建失败，日志末尾："
  tail -n 15 "$work/build.log" | sed 's/^/        /'
  return 1
}
bundle_has() { # <固定字符串>：浏览器包里出现则返回 1
  ! grep -rqF -- "$1" "$app/.next/static"
}

# S6：写入口先做同源校验。写入口 = 含 "use server" 的文件 + 导出 POST / PUT / PATCH / DELETE 的路由文件；
# 在每个写入口里，isSameOriginRequest( 必须出现在第一次读会话 / 调 UAM 之前。
write_entry_files() {
  {
    grep -rl '"use server"' "$app/src" || true
    grep -rlE 'export (async )?function (POST|PUT|PATCH|DELETE)\b' "$app/src" || true
  } | sort -u
}
EXPECTED_WRITE_ENTRIES="
src/app/(auth)/login/actions.ts
src/app/(shell)/account/password/actions.ts
src/app/(shell)/sessions/[userId]/actions.ts
src/app/(shell)/users/[userId]/actions.ts
src/app/(shell)/users/new/actions.ts
src/app/logout/route.ts
"
s6_entries_complete() { # 发现的写入口 = 预期清单；新增写入口必须同时更新清单并满足下面的检查
  local found expected
  found="$(write_entry_files | sed "s#^$app/##")"
  expected="$(printf '%s' "$EXPECTED_WRITE_ENTRIES" | sed '/^$/d' | sort -u)"
  if [ "$found" != "$expected" ]; then
    echo "        发现的写入口与预期清单不一致："
    diff <(printf '%s\n' "$expected") <(printf '%s\n' "$found") | sed 's/^/        /' || true
    return 1
  fi
}
s6_origin_first() { # <文件>：第一个 await（headers() 除外，它只是取请求头）必须在同源校验之后
  local f="$1" origin_line first_await
  origin_line="$(grep -n 'isSameOriginRequest(' "$f" | head -n1 | cut -d: -f1)"
  first_await="$(grep -nE '[^A-Za-z]await ' "$f" | grep -vE '^[0-9]+:[[:space:]]*(//|\*|/\*)' | grep -v 'headers()' | head -n1 | cut -d: -f1)"
  [ -n "$origin_line" ] && [ -n "$first_await" ] && [ "$origin_line" -lt "$first_await" ]
}
s6_unit_tests() { # 跑「跨站 / 同源」相关的单元测试，并确认每个写入口的测试文件都真的跑到了用例
  [ -d "$app/node_modules" ] || {
    echo "        缺少 apps/admin-center/node_modules"
    return 1
  }
  (cd "$app" && npx --no-install jest \
    tests/unit/app/login-actions.test.ts tests/unit/app/logout-route.test.ts \
    tests/unit/app/new-user-actions.test.ts tests/unit/app/user-status-actions.test.ts \
    tests/unit/app/terminate-actions.test.ts tests/unit/app/password-actions.test.ts \
    tests/unit/lib/security/origin-check.test.ts \
    -t '跨站|同源' --json --outputFile="$work/jest.json" >"$work/jest.log" 2>&1) || {
    tail -n 20 "$work/jest.log" | sed 's/^/        /'
    return 1
  }
  # shellcheck disable=SC2016  # 单引号里是 node 脚本，反引号模板字符串不是 shell 展开
  node -e '
    const r = require(process.argv[1]);
    let bad = 0, total = 0;
    for (const f of r.testResults) {
      const passed = f.assertionResults.filter((a) => a.status === "passed").length;
      const failed = f.assertionResults.filter((a) => a.status === "failed").length;
      total += passed;
      console.log(`        ${f.name.split("/tests/")[1]}：通过 ${passed}，失败 ${failed}`);
      if (passed === 0 || failed > 0) bad = 1;
    }
    console.log(`        合计通过 ${total}`);
    process.exit(bad);
  ' "$work/jest.json"
}

run_static() {
  section "静态检查（无需 Docker）"
  echo "S1 被跟踪的 .env 文件"
  check "没有被跟踪的 .env（只允许 *.example 与 infra/local/e2e.env）" s1_env_files
  check "e2e.env 文件头标注「仅限本地 E2E」" s1_e2e_env_marked
  echo "S2 Admin Center 不含数据库驱动 / ORM（FR-013）"
  check "apps/admin-center/package.json 的 dependencies 与 devDependencies 里没有数据库驱动、ORM 或缓存客户端" s2_no_db_deps
  echo "S3 密钥与私钥特征（工作区 + 全部历史）"
  check "工作区没有完整长度的 uamk_ 密钥或私钥特征（允许名单：$ALLOW_KEY_FILE）" s3_worktree
  check "全部提交历史（git log --all -p）没有完整长度的 uamk_ 密钥或私钥特征（允许名单：e2e.env + 两处遗留占位 PEM）" s3_history
  check "允许名单文件只含一把标注过的测试密钥，且没有私钥特征" s3_allowlist_minimal
  echo "S4 src/lib 服务端模块都有 import \"server-only\""
  check "允许名单里的客户端安全文件都真实存在" s4_allowlist_exists
  check "其余 src/lib 模块都有 import \"server-only\"" s4_server_only
  echo "S5 浏览器包（.next/static）里没有服务密钥"
  if check "npm run build（不设 UAM_SERVICE_KEY / UAM_BASE_URL）成功" s5_build; then
    local e2e_key
    e2e_key="$UAM_SERVICE_KEY"
    check "浏览器包里没有 e2e.env 的测试密钥值" bundle_has "$e2e_key"
    check "浏览器包里没有 UAM_SERVICE_KEY" bundle_has "UAM_SERVICE_KEY"
    check "浏览器包里没有 uamk_" bundle_has "uamk_"
  fi
  echo "S6 写入口先做同源校验"
  check "写入口清单完整（5 个 Server Action 文件 + POST /logout 路由）" s6_entries_complete
  local f
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    check "$f：isSameOriginRequest 先于任何异步调用（读会话、调 UAM）" s6_origin_first "$app/$f"
  done < <(printf '%s' "$EXPECTED_WRITE_ENTRIES" | sed '/^$/d')
  check "跨站 / 同源相关的单元测试全部通过（每个写入口的测试文件都有用例）" s6_unit_tests
}

# ============================ 运行期检查 ============================

stack_is_running() {
  [ -n "$(stack_compose ps --status running -q admin-center 2>/dev/null)" ] &&
    [ -n "$(stack_compose ps --status running -q uam 2>/dev/null)" ] &&
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/login" 2>/dev/null || true)" = "200" ]
}

http_n=0
LAST=""
LAST_STATUS=""
MODE_HDRS=()
# 每个请求的完整响应（头 + 体）存一份，供最后的泄露扫描；登录响应里合法的会话 Set-Cookie 在存盘前去掉（先断言了它的属性）
next_capture() {
  http_n=$((http_n + 1))
  LAST="$work/http/$(printf '%03d' "$http_n").txt"
}
status_of() { head -n1 "$LAST" | awk '{print $2}'; }

# 跨站伪造的四种来源：same 对照；foreign 外站 Origin；xsite Origin 同源但 Sec-Fetch-Site: cross-site；none 两者都没有
mode_headers() {
  case "$1" in
    same) MODE_HDRS=(-H "Origin: $BASE") ;;
    foreign) MODE_HDRS=(-H "Origin: http://evil.example") ;;
    xsite) MODE_HDRS=(-H "Origin: $BASE" -H "Sec-Fetch-Site: cross-site") ;;
    none) MODE_HDRS=(-H "X-Check-Mode: no-origin") ;; # 惰性头：只为避免空数组
  esac
}

get_page() { # <路径> [会话标识]：结果在 $LAST / $LAST_STATUS
  local args=()
  [ -z "${2:-}" ] || args+=(-H "Cookie: $COOKIE_NAME=$2")
  next_capture
  curl -sS --max-time 20 -i ${args[@]+"${args[@]}"} "$BASE$1" >"$LAST"
  LAST_STATUS="$(status_of)"
}

# 表单提交（与浏览器无 JS 时一致，见 scale-lib.sh 的 web_login）：取页面里 Next 的隐藏字段，多段表单提交。
form_post() { # <路径> <来源模式> <会话标识或空> [字段=值 ...]
  local path="$1" fmode="$2" sid="$3" page line name value
  shift 3
  local args=() hdrs=() f
  [ -z "$sid" ] || hdrs+=(-H "Cookie: $COOKIE_NAME=$sid")
  page="$(curl -sS --max-time 20 ${hdrs[@]+"${hdrs[@]}"} "$BASE$path")"
  while IFS= read -r line; do
    name="$(printf '%s' "$line" | sed -E 's/.*name="([^"]*)".*/\1/')"
    value="$(printf '%s' "$line" | sed -nE 's/.*value="([^"]*)".*/\1/p' | sed 's/&quot;/"/g')"
    args+=(--form-string "$name=$value")
  done < <(printf '%s' "$page" | grep -o '<input type="hidden"[^>]*>')
  for f in "$@"; do args+=(--form-string "$f"); done
  mode_headers "$fmode"
  next_capture
  curl -sS --max-time 30 -i ${hdrs[@]+"${hdrs[@]}"} "${MODE_HDRS[@]}" "${args[@]}" "$BASE$path" >"$LAST"
  LAST_STATUS="$(status_of)"
}

# Server Action 的直接调用（停用 / 启用 / 强制下线是按钮触发的绑定参数动作，没有表单）：
# 请求头 Next-Action + text/plain 的 JSON 参数数组；动作 id 从该页面加载的脚本里按导出名找（见 discover_action）。
action_post() { # <路径> <动作 id> <JSON 参数> <来源模式> <会话标识>
  mode_headers "$4"
  next_capture
  curl -sS --max-time 30 -i -H "Cookie: $COOKIE_NAME=$5" -H "Next-Action: $2" \
    -H 'Content-Type: text/plain;charset=UTF-8' -H 'Accept: text/x-component' \
    "${MODE_HDRS[@]}" --data "$3" "$BASE$1" >"$LAST"
  LAST_STATUS="$(status_of)"
}

discover_action() { # <页面路径> <会话标识> <动作导出名> → 标准输出动作 id（找不到则无输出）
  local html src id
  html="$(curl -sS --max-time 20 -H "Cookie: $COOKIE_NAME=$2" "$BASE$1")"
  for src in $(printf '%s' "$html" | grep -o '<script src="[^"]*"' | sed 's/^<script src="//; s/"$//'); do
    id="$(curl -gsS --max-time 20 "$BASE$src" | sed -nE "s/.*createServerReference\)\(\"([0-9a-f]+)\",[^)]*\"$3\"\).*/\1/p" | head -n1)"
    if [ -n "$id" ]; then
      printf '%s' "$id"
      return 0
    fi
  done
  return 0
}

# 登录并取会话标识（全局 LOGIN_SID）。成功（303）时断言 Set-Cookie 属性后，把该 Set-Cookie 行从存盘的响应里去掉。
LOGIN_SID=""
login_as() { # <账号> <密码>
  form_post /login same "" "accountName=$1" "password=$2"
  LOGIN_SID=""
  [ "$LAST_STATUS" = "303" ] || return 1
  local line
  line="$(tr -d '\r' <"$LAST" | grep -i "^set-cookie: $COOKIE_NAME=" | head -n1)"
  LOGIN_SID="$(printf '%s' "$line" | sed -E "s/^[Ss]et-[Cc]ookie: $COOKIE_NAME=([^;]*).*/\\1/")"
  LOGIN_COOKIE_LINE="$(printf '%s' "$line" | sed -E "s/($COOKIE_NAME=)[^;]*/\\1<已隐藏>/")"
  grep -vi "^set-cookie: $COOKIE_NAME=" "$LAST" >"$LAST.tmp" && mv "$LAST.tmp" "$LAST"
  [ -n "$LOGIN_SID" ]
}
LOGIN_COOKIE_LINE=""
cookie_attrs_ok() { # LOGIN_COOKIE_LINE 含 HttpOnly; Secure; SameSite=strict（大小写不敏感）
  local l
  l="$(printf '%s' "$LOGIN_COOKIE_LINE" | tr '[:upper:]' '[:lower:]')"
  case "$l" in *"httponly"*) ;; *) return 1 ;; esac
  case "$l" in *"secure"*) ;; *) return 1 ;; esac
  case "$l" in *"samesite=strict"*) ;; *) return 1 ;; esac
}

last_has() { grep -qF -- "$1" "$LAST"; }
last_has_setcookie() { grep -qi '^set-cookie:' "$LAST"; }
# 被拒：状态码 >= 400（Next 自带的 Origin 校验返回 500），或 200 且页面 / 动作返回里带「来源校验失败」（应用层校验）
rejected_with_notice() { [ "$LAST_STATUS" = "200" ] && last_has "来源校验失败"; }
rejected_by_framework() { [ "$LAST_STATUS" -ge 400 ] 2>/dev/null; }

# 用户状态：从用户列表的表格行取「正常 / 已停用」；找不到输出 missing；n 为该用户名行数
user_row_states() { # <会话标识> <用户名>
  curl -sS --max-time 20 -H "Cookie: $COOKIE_NAME=$1" "$BASE/users?q=$2" | sed 's/<[^>]*>/ /g' | tr -s ' ' |
    grep -oE "$2 (正常|已停用)" | sed "s/^$2 //" || true
}
user_status() { # <会话标识> <用户名>
  local s
  s="$(user_row_states "$1" "$2" | head -n1)"
  printf '%s' "${s:-missing}"
}
user_row_count() { user_row_states "$1" "$2" | wc -l | tr -d ' '; }

# 一个写入口的伪造矩阵：对 foreign / xsite / none 三种来源各打一次，要求被拒；状态不变由调用方在每次之后检查
# 不能直接泛化的是「怎么发请求」与「状态不变」，所以这里只封装「期望被拒」的判定：
#   foreign → 被框架拒（>= 400）或应用层拒；xsite / none → 应用层「来源校验失败」
expect_rejected() { # <描述前缀> <来源模式>
  case "$2" in
    foreign) if rejected_by_framework || rejected_with_notice; then ok "$1 来源=外站 Origin：被拒（HTTP $LAST_STATUS）"; else bad "$1 来源=外站 Origin：未被拒（HTTP $LAST_STATUS）"; fi ;;
    xsite) if rejected_with_notice; then ok "$1 来源=Sec-Fetch-Site: cross-site：被拒（应用层）"; else bad "$1 来源=Sec-Fetch-Site: cross-site：未被拒（HTTP $LAST_STATUS）"; fi ;;
    none) if rejected_with_notice; then ok "$1 来源=无 Origin 且无 Sec-Fetch-Site：被拒（应用层）"; else bad "$1 来源=无 Origin 且无 Sec-Fetch-Site：未被拒（HTTP $LAST_STATUS）"; fi ;;
  esac
}

run_runtime() {
  section "运行期检查（对运行中的 E2E 栈：$BASE）"
  local run
  run="$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
  # 标记值：被注入请求的密码；它们、固定的管理员密码、服务密钥、会话标识与哈希前缀都不得出现在任何响应或日志里
  local bad_login_pw="Mk-BadLogin-${run}-1" init_pw="Mk-Init-${run}-Pw1" wrong_cur_pw="Mk-WrongCur-${run}-1" new_pw="Mk-NewPw-${run}-9"
  local acct="sc${run}" uname="SecCheck-${run}" mode m
  local sid_admin sid_t sid_t2 sid_out

  echo "R0 准备：登录管理员（对照 + 取会话）"
  if ! login_as "$ADMIN_ACCOUNT" "$ADMIN_PASSWORD"; then
    bad "管理员登录失败（HTTP $LAST_STATUS）：栈是否用 bootstrap.sh 启动、首个用户是否存在"
    return
  fi
  sid_admin="$LOGIN_SID"
  ok "管理员登录成功（303）"
  check "登录响应的会话 Set-Cookie 带 HttpOnly; Secure; SameSite=Strict（$LOGIN_COOKIE_LINE）" cookie_attrs_ok

  echo "R1 登录入口"
  form_post /login same "" "accountName=$ADMIN_ACCOUNT" "password=$bad_login_pw"
  if [ "$LAST_STATUS" = "200" ] && last_has "账号名或密码错误" && ! last_has_setcookie; then ok "错误密码：统一提示、不发 cookie"; else bad "错误密码的响应不符合预期（HTTP $LAST_STATUS）"; fi
  for mode in foreign xsite none; do
    form_post /login "$mode" "" "accountName=$ADMIN_ACCOUNT" "password=$ADMIN_PASSWORD"
    expect_rejected "login 动作（密码正确）" "$mode"
    if last_has_setcookie; then bad "login 动作 来源=$mode：被拒的请求仍发了 Set-Cookie"; else ok "login 动作 来源=$mode：没有发 cookie"; fi
  done

  echo "R2 浏览页面（登录后）"
  for m in /users /users/new /sessions /account/password; do
    get_page "$m" "$sid_admin"
    assert_eq "GET $m（已登录）" "$LAST_STATUS" "200"
  done

  echo "R3 新建用户（createUser）"
  local c_before
  for mode in foreign xsite none; do
    form_post /users/new "$mode" "$sid_admin" "userName=$uname" "accountName=${acct}x" "password=$init_pw"
    expect_rejected "createUser" "$mode"
  done
  assert_eq "被拒的新建请求之后用户列表里没有该用户（状态不变）" "$(user_status "$sid_admin" "$uname")" "missing"
  c_before="$(user_row_count "$sid_admin" "$uname")"
  form_post /users/new same "$sid_admin" "userName=$uname" "accountName=$acct" "password=$init_pw"
  local t_id
  t_id="$(tr -d '\r' <"$LAST" | sed -nE 's#^[Ll]ocation: /users/([0-9a-f-]{36})\?created=1.*#\1#p' | head -n1)"
  if [ "$LAST_STATUS" = "303" ] && [ -n "$t_id" ]; then ok "同源新建（对照）：303 到详情页"; else bad "同源新建（对照）失败（HTTP $LAST_STATUS）"; return; fi
  assert_eq "新建后该用户名恰有 1 行（此前 $c_before 行）、状态正常" "$(user_row_count "$sid_admin" "$uname") $(user_status "$sid_admin" "$uname")" "1 正常"

  echo "R4 新用户登录并强制下线（terminateSession）"
  if ! login_as "$acct" "$init_pw"; then bad "新用户登录失败（HTTP $LAST_STATUS）"; return; fi
  sid_t="$LOGIN_SID"
  ok "新用户登录成功"
  local ref terminate_id disable_id enable_id
  get_page "/sessions/$t_id" "$sid_admin"
  ref="$(sed -nE 's/.*sessionRef[^0-9a-f]+([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}).*/\1/p' "$LAST" | head -n1)"
  terminate_id="$(discover_action "/sessions/$t_id" "$sid_admin" terminateSession)"
  if [ -z "$ref" ] || [ -z "$terminate_id" ]; then
    bad "无法取得会话引用或 terminateSession 的动作 id（引用：${ref:+有}${ref:-无}，id：${terminate_id:+有}${terminate_id:-无}）"
  else
    for mode in foreign xsite none; do
      action_post "/sessions/$t_id" "$terminate_id" "[\"$t_id\",\"$ref\"]" "$mode" "$sid_admin"
      expect_rejected "terminateSession" "$mode"
      assert_eq "terminateSession 来源=$mode 之后目标用户的会话仍有效（状态不变）" "$(page_state "$BASE" "$sid_t" /users)" "ok"
    done
    action_post "/sessions/$t_id" "$terminate_id" "[\"$t_id\",\"$ref\"]" same "$sid_admin"
    assert_eq "同源强制下线（对照）：目标用户的会话失效" "$(page_state "$BASE" "$sid_t" /users)" "loggedout"
  fi

  echo "R5 停用 / 启用（disableUser、enableUser）"
  disable_id="$(discover_action "/users/$t_id" "$sid_admin" disableUser)"
  enable_id="$(discover_action "/users/$t_id" "$sid_admin" enableUser)"
  if [ -z "$disable_id" ] || [ -z "$enable_id" ]; then
    bad "无法取得 disableUser / enableUser 的动作 id"
  else
    for mode in foreign xsite none; do
      action_post "/users/$t_id" "$disable_id" "[\"$t_id\"]" "$mode" "$sid_admin"
      expect_rejected "disableUser" "$mode"
      assert_eq "disableUser 来源=$mode 之后用户状态仍为正常" "$(user_status "$sid_admin" "$uname")" "正常"
    done
    action_post "/users/$t_id" "$disable_id" "[\"$t_id\"]" same "$sid_admin"
    assert_eq "同源停用（对照）：状态变为已停用" "$(user_status "$sid_admin" "$uname")" "已停用"
    for mode in foreign xsite none; do
      action_post "/users/$t_id" "$enable_id" "[\"$t_id\"]" "$mode" "$sid_admin"
      expect_rejected "enableUser" "$mode"
      assert_eq "enableUser 来源=$mode 之后用户状态仍为已停用" "$(user_status "$sid_admin" "$uname")" "已停用"
    done
    action_post "/users/$t_id" "$enable_id" "[\"$t_id\"]" same "$sid_admin"
    assert_eq "同源启用（对照）：状态恢复为正常" "$(user_status "$sid_admin" "$uname")" "正常"
  fi

  echo "R6 修改自己的密码（changePassword）"
  if ! login_as "$acct" "$init_pw"; then bad "新用户重新登录失败（HTTP $LAST_STATUS）"; return; fi
  sid_t2="$LOGIN_SID"
  for mode in foreign xsite none; do
    form_post /account/password "$mode" "$sid_t2" "currentPassword=$init_pw" "newPassword=$new_pw" "confirmPassword=$new_pw"
    expect_rejected "changePassword（当前密码正确）" "$mode"
  done
  if login_as "$acct" "$new_pw"; then bad "被拒的改密请求之后新密码竟然可以登录（密码被改了）"; else ok "被拒的改密请求之后新密码不能登录（密码未改）"; fi
  form_post /account/password same "$sid_t2" "currentPassword=$wrong_cur_pw" "newPassword=$new_pw" "confirmPassword=$new_pw"
  if [ "$LAST_STATUS" = "200" ] && last_has "当前密码不正确"; then ok "同源 + 错误的当前密码：提示「当前密码不正确」"; else bad "错误当前密码的响应不符合预期（HTTP $LAST_STATUS）"; fi
  form_post /account/password same "$sid_t2" "currentPassword=$init_pw" "newPassword=$new_pw" "confirmPassword=$new_pw"
  if [ "$LAST_STATUS" = "200" ] && last_has "密码已修改"; then ok "同源改密（对照）：提示「密码已修改」"; else bad "同源改密（对照）响应不符合预期（HTTP $LAST_STATUS）"; fi
  # 账号与会话一对一（FR-004）：再次登录会让 sid_t2 失效，退出用例改用最新的这个
  sid_out=""
  if login_as "$acct" "$new_pw"; then
    ok "改密后新密码可以登录"
    sid_out="$LOGIN_SID"
  else
    bad "改密后新密码不能登录"
    return
  fi

  echo "R7 退出登录（POST /logout）"
  for mode in foreign xsite none; do
    mode_headers "$mode"
    next_capture
    curl -sS --max-time 20 -i -X POST -H "Cookie: $COOKIE_NAME=$sid_out" "${MODE_HDRS[@]}" "$BASE/logout" >"$LAST"
    LAST_STATUS="$(status_of)"
    assert_eq "POST /logout 来源=$mode：403" "$LAST_STATUS" "403"
    if last_has_setcookie; then bad "POST /logout 来源=$mode：被拒的请求清了 cookie"; else ok "POST /logout 来源=$mode：没有 Set-Cookie"; fi
    assert_eq "POST /logout 来源=$mode 之后会话仍有效" "$(page_state "$BASE" "$sid_out" /users)" "ok"
  done
  next_capture
  curl -sS --max-time 20 -i -H "Cookie: $COOKIE_NAME=$sid_out" "$BASE/logout" >"$LAST"
  LAST_STATUS="$(status_of)"
  assert_eq "GET /logout：405（不能用链接 / 图片触发退出）" "$LAST_STATUS" "405"
  mode_headers same
  next_capture
  curl -sS --max-time 20 -i -X POST -H "Cookie: $COOKIE_NAME=$sid_out" "${MODE_HDRS[@]}" "$BASE/logout" >"$LAST"
  LAST_STATUS="$(status_of)"
  assert_eq "同源 POST /logout（对照）：303" "$LAST_STATUS" "303"
  assert_eq "同源退出之后会话失效" "$(page_state "$BASE" "$sid_out" /users)" "loggedout"

  echo "R9 泄露扫描（SC-014）"
  # 管理员会话在最后才退出，让上面所有请求都用得到
  mode_headers same
  next_capture
  curl -sS --max-time 20 -i -X POST -H "Cookie: $COOKIE_NAME=$sid_admin" "${MODE_HDRS[@]}" "$BASE/logout" >"$LAST"
  LAST_STATUS="$(status_of)"
  assert_eq "管理员退出：303" "$LAST_STATUS" "303"

  stack_compose logs --no-color --no-log-prefix uam >"$work/uam.log" 2>&1 || true
  stack_compose logs --no-color --no-log-prefix admin-center >"$work/admin-center.log" 2>&1 || true
  local need_logs
  need_logs="$(wc -l <"$work/uam.log" | tr -d ' ') 行 UAM、$(wc -l <"$work/admin-center.log" | tr -d ' ') 行 Admin Center"
  ok "已取得日志（$need_logs）与 $http_n 个 HTTP 响应的完整存档（头 + 体）"

  # 浏览器包：运行中栈提供的脚本
  local js
  grep -rhoE '/_next/static/[^"]*\.js' "$work/http" | sort -u >"$work/js-list.txt" || true
  while IFS= read -r js; do
    [ -n "$js" ] || continue
    curl -gsS --max-time 20 "$BASE$js" -o "$work/static/$(printf '%s' "$js" | tr '/()%[]' '_______')"
  done <"$work/js-list.txt"
  local nstatic
  nstatic="$(find "$work/static" -type f | wc -l | tr -d ' ')"
  if [ "$nstatic" -gt 0 ]; then ok "取得运行中栈提供的 $nstatic 个浏览器脚本"; else bad "没有取得任何浏览器脚本"; fi

  local label value dir
  while IFS='|' read -r label value; do
    [ -n "$value" ] || continue
    for dir in "$work/http:HTTP 响应" "$work/uam.log:UAM 日志" "$work/admin-center.log:Admin Center 日志" "$work/static:运行中栈的浏览器脚本"; do
      if grep -rqF -- "$value" "${dir%%:*}"; then
        bad "${dir#*:}里出现了「$label」"
      else
        ok "${dir#*:}里没有「$label」"
      fi
    done
  done <<EOF
新建用户的初始密码标记|$init_pw
改密的当前密码标记（错误值）|$wrong_cur_pw
改密的新密码标记|$new_pw
登录错误密码标记|$bad_login_pw
管理员密码|$ADMIN_PASSWORD
服务密钥（e2e.env）|$UAM_SERVICE_KEY
管理员会话标识|$sid_admin
新用户会话标识（强制下线的）|$sid_t
新用户会话标识（改密用的）|$sid_t2
新用户会话标识（退出用的）|$sid_out
密码哈希前缀 \$argon2|\$argon2
EOF
}

# ============================ 入口 ============================

case "$mode" in
  static) run_static ;;
  runtime | all)
    if [ "$mode" = "all" ]; then run_static; fi
    if ! stack_is_running; then
      if [ "$mode" = "runtime" ]; then
        echo "E2E 栈没有在运行：先执行 infra/local/scripts/bootstrap.sh up（或用 all 模式让脚本自己起栈）" >&2
        exit 2
      fi
      log "E2E 栈未运行：用 bootstrap.sh up 启动（结束时关闭）"
      started_stack=1
      "$here/bootstrap.sh" up >"$work/bootstrap.log" 2>&1 || {
        tail -n 20 "$work/bootstrap.log" >&2
        echo "bootstrap.sh up 失败" >&2
        exit 2
      }
    fi
    run_runtime
    ;;
esac

printf '\n结果：%d 项通过，%d 项失败\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
