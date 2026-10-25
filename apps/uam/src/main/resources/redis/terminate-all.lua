-- 按账号终止：一次清理这些账号当前会话的 sess / sref / acct / online 四处（停用用户，research R15）。
-- KEYS[1] uam:online      ARGV: 各账号标识
-- 经 acct 指针找到会话；返回实际终止的会话数（指针悬空而会话本已不存在时只清指针，不计数）。
local terminated = 0
for _, accountId in ipairs(ARGV) do
  local acctKey = 'uam:acct:' .. accountId .. ':sess'
  local sha = redis.call('GET', acctKey)
  if sha then
    local sessKey = 'uam:sess:' .. sha
    local ref = redis.call('HGET', sessKey, 'ref')
    local userId = redis.call('HGET', sessKey, 'userId')
    redis.call('DEL', sessKey)
    redis.call('DEL', acctKey)
    if ref then
      redis.call('DEL', 'uam:sref:' .. ref)
      if userId then
        redis.call('ZREM', KEYS[1], userId .. '|' .. ref)
      end
      terminated = terminated + 1
    end
  end
end
return terminated
