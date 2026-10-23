-- 按会话引用终止：一次清理 sess / sref / acct / online 四处（research R15）。
-- KEYS[1] uam:sref:{ref}   KEYS[2] uam:online      ARGV: 1 ref
-- acct 只在仍指向该会话时才删，避免误删同账号随后建立的新会话。返回 1 = 已终止，0 = 本来就不存在。
local sha = redis.call('GET', KEYS[1])
if not sha then
  return 0
end
local sessKey = 'uam:sess:' .. sha
local userId = redis.call('HGET', sessKey, 'userId')
local accountId = redis.call('HGET', sessKey, 'accountId')
redis.call('DEL', sessKey)
redis.call('DEL', KEYS[1])
if accountId then
  local acctKey = 'uam:acct:' .. accountId .. ':sess'
  if redis.call('GET', acctKey) == sha then
    redis.call('DEL', acctKey)
  end
end
if userId then
  redis.call('ZREM', KEYS[2], userId .. '|' .. ARGV[1])
end
return 1
