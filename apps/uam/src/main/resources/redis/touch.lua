-- 续期：会话仍存在才写；三个键的 TTL 一起刷新，并更新在线索引的分数（research R8、R9）。
-- KEYS[1] uam:sess:{sha}   KEYS[2] uam:acct:{accountId}:sess   KEYS[3] uam:sref:{ref}   KEYS[4] uam:online
-- ARGV: 1 sha  2 lastActiveAt(ms)  3 expiresAt(ms)  4 ttl(ms)  5 online 成员  6 online 分数
-- 返回 0 = 会话已不存在（不重建）；1 = 已续期；2 = 已有更新的写入，无需再写。
if redis.call('EXISTS', KEYS[1]) == 0 then
  return 0
end
local last = tonumber(redis.call('HGET', KEYS[1], 'lastActiveAt'))
if last and tonumber(ARGV[2]) <= last then
  return 2
end
redis.call('HSET', KEYS[1], 'lastActiveAt', ARGV[2], 'expiresAt', ARGV[3])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
if redis.call('GET', KEYS[2]) == ARGV[1] then
  redis.call('PEXPIRE', KEYS[2], ARGV[4])
end
if redis.call('GET', KEYS[3]) == ARGV[1] then
  redis.call('PEXPIRE', KEYS[3], ARGV[4])
end
redis.call('ZADD', KEYS[4], ARGV[6], ARGV[5])
return 1
