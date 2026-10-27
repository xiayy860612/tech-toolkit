#!/usr/bin/env bash
# 造压测数据（T164，quickstart S15 的前置）：默认 10 万用户（含账号与凭据）与 1 万个同时有效的会话。
# 在 devops/local/docker-compose.scale.yml 的多实例栈上运行（先 devops/local/scripts/scale-checks.sh up）。
#
# 做法：
#   用户 / 账号 / 凭据：直接用 SQL（psql，generate_series）生成行。Argon2id 逐个哈希 10 万次太慢，所以所有造数账号
#                       复用同一个预先算好的哈希——取自管理员 admin 的凭据（真实的 Argon2id 输出），因此造数账号的登录
#                       密码就是管理员的本地测试密码。这只改变「哈希值相同」这一点，行结构与索引与 UAM 自己写入的一致。
#   会话：              由 k6（seed-sessions.js）经 uam-lb 调用 UAM 真实的登录接口生成，Argon2id 校验照常进行，
#                       会话与 uam:online 索引就是 UAM 自己写的结构（比直接写 Redis 更保真，约几十秒到数分钟）。
# 幂等：用户按序号补齐缺的部分（已有的不动）；会话重新登录一遍（同账号的旧会话被顶掉，数量不变）。
#
# 用法：devops/load/k6/seed-data.sh
# 环境变量：USERS（默认 100000）、SESSIONS（默认 10000，不得大于 USERS）、SEED_VUS（造会话并发，默认 48）
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../local/scripts/scale-lib.sh
source "$here/../../local/scripts/scale-lib.sh"

users="${USERS:-100000}"
sessions="${SESSIONS:-10000}"
seed_vus="${SEED_VUS:-48}"
# 先校验是纯数字：USERS 会原样进 SQL，`[ -gt ]` 对非数字也只是报错而不一定中止
for v in users sessions seed_vus; do
  case "${!v}" in
    '' | *[!0-9]*) echo "$v 必须是非负整数，收到：${!v}" >&2; exit 2 ;;
  esac
done
if [ "$sessions" -gt "$users" ]; then
  echo "SESSIONS（$sessions）不能大于 USERS（$users）" >&2
  exit 2
fi
stack_is_up || { echo "多实例栈未启动：先运行 devops/local/scripts/scale-checks.sh up" >&2; exit 2; }

psql_run() { compose exec -T postgres psql -U uam -d uam -v ON_ERROR_STOP=1 -q "$@"; }

# 管理员账号名经 psql 变量（:'acct' 自动加引号转义）传入，不拼进 SQL 文本；变量替换只对标准输入生效，对 -c 无效，所以用 heredoc
admin_hash="$(psql_run -At -v acct="$ADMIN_ACCOUNT" <<'SQL'
SELECT c.password_hash FROM credential c JOIN account a ON a.id = c.account_id WHERE a.login_name_norm = lower(:'acct')
SQL
)"
[ -n "$admin_hash" ] || { echo "找不到管理员 $ADMIN_ACCOUNT 的凭据（先运行 scale-checks.sh up 建首个用户）" >&2; exit 2; }

log "生成用户：目标 $users 个（只补缺的）"
started="$(date +%s)"
# 整段放在一个事务里：中途失败（ON_ERROR_STOP）整体回滚，不留下只有用户没有账号 / 凭据的孤儿行，重跑也不会重复补
psql_run -v n="$users" -v hash="$admin_hash" <<'SQL'
BEGIN;
CREATE TEMP TABLE seed_new AS
  SELECT i, gen_random_uuid() AS uid, gen_random_uuid() AS aid, 'load' || lpad(i::text, 6, '0') AS login
  FROM generate_series(1, :n) AS i
  WHERE NOT EXISTS (SELECT 1 FROM account WHERE login_name_norm = 'load' || lpad(i::text, 6, '0'));
INSERT INTO app_user (id, name, status, created_at, updated_at)
  SELECT uid, '负载用户' || lpad(i::text, 6, '0'), 'ACTIVE', now() - (i || ' seconds')::interval, now() FROM seed_new;
INSERT INTO account (id, user_id, kind, login_name, login_name_norm, status, created_at)
  SELECT aid, uid, 'PASSWORD', login, login, 'ACTIVE', now() - (i || ' seconds')::interval FROM seed_new;
INSERT INTO credential (account_id, password_hash, updated_at)
  SELECT aid, :'hash', now() FROM seed_new;
COMMIT;
ANALYZE app_user;
ANALYZE account;
ANALYZE credential;
SQL
log "用户已就绪（耗时 $(($(date +%s) - started)) 秒）：$(psql_run -At -c "SELECT count(*) || ' 用户 / ' || (SELECT count(*) FROM account) || ' 账号 / ' || (SELECT count(*) FROM credential) || ' 凭据' FROM app_user")"

log "生成会话：$sessions 个（经 UAM 真实登录接口，并发 $seed_vus）"
started="$(date +%s)"
compose --profile load run --rm -T k6 run \
  -e SESSIONS="$sessions" -e SEED_VUS="$seed_vus" --quiet /scripts/seed-sessions.js
log "会话已就绪（耗时 $(($(date +%s) - started)) 秒）；空闲时限 30 分钟，请尽快压测"
