-- 登录：原子完成「读旧 → 删旧 → 写新 → 更新索引」，保证账号与会话一一对应（research R7、R9）。
-- KEYS[1] uam:acct:{accountId}:sess   KEYS[2] uam:sess:{sha}   KEYS[3] uam:sref:{ref}   KEYS[4] uam:online
-- ARGV: 1 sha  2 ref  3 accountId  4 userId  5 userName  6 loginName  7 kind
--       8 loginAt(ms)  9 lastActiveAt(ms)  10 absExpiresAt(ms)  11 expiresAt(ms)
--       12 ttl(ms)  13 online 成员 {userId}|{ref}  14 online 分数（到期 epoch 秒）
-- 返回被顶掉的旧会话引用，没有则为 nil。
local replaced = false
local old = redis.call('GET', KEYS[1])
if old then
  local oldKey = 'uam:sess:' .. old
  local oldRef = redis.call('HGET', oldKey, 'ref')
  local oldUser = redis.call('HGET', oldKey, 'userId')
  redis.call('DEL', oldKey)
  if oldRef then
    redis.call('DEL', 'uam:sref:' .. oldRef)
    if oldUser then
      redis.call('ZREM', KEYS[4], oldUser .. '|' .. oldRef)
    end
    replaced = oldRef
  end
end
redis.call('HSET', KEYS[2],
  'ref', ARGV[2], 'accountId', ARGV[3], 'userId', ARGV[4], 'userName', ARGV[5],
  'loginName', ARGV[6], 'kind', ARGV[7], 'loginAt', ARGV[8], 'lastActiveAt', ARGV[9],
  'absExpiresAt', ARGV[10], 'expiresAt', ARGV[11])
redis.call('PEXPIRE', KEYS[2], ARGV[12])
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[12])
redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[12])
redis.call('ZADD', KEYS[4], ARGV[14], ARGV[13])
return replaced
