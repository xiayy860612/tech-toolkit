-- 在线用户索引：先清掉已到期的条目，再列出仍然有效的会话及其最近活动时间（research R9）。
-- KEYS[1] uam:online      ARGV: 1 now(epoch 秒)  2 now(ms)
-- 返回扁平数组 [member1, lastActiveAt1(ms), member2, lastActiveAt2(ms), ...]，member 为 {userId}|{ref}。
-- 即使会话键还在，只要有效到期已过（expiresAt <= now）或会话本身已不存在，也不返回。
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
local out = {}
for _, member in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
  local sep = string.find(member, '|', 1, true)
  if sep then
    local sha = redis.call('GET', 'uam:sref:' .. string.sub(member, sep + 1))
    if sha then
      local fields = redis.call('HMGET', 'uam:sess:' .. sha, 'lastActiveAt', 'expiresAt')
      if fields[1] and fields[2] and tonumber(fields[2]) > tonumber(ARGV[2]) then
        out[#out + 1] = member
        out[#out + 1] = fields[1]
      end
    end
  end
end
return out
