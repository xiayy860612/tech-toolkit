-- 限流闸门：在（较慢的）哈希比对之前原子占用一次尝试名额，并发的 N 个尝试不会同时通过闸门。
-- INCR；键刚建立（或意外没有 TTL）时设为失败窗口；计数恰好达到上限时把 TTL 重置为封锁时长；
-- 计数超过上限则拒绝（不刷新 TTL），返回剩余毫秒（Retry-After）；放行返回 0。
-- KEYS[1] uam:throttle:{login|pwchg}:{subject}    ARGV: 1 max, 2 block(ms)
local count = redis.call('INCR', KEYS[1])
if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
local max = tonumber(ARGV[1])
if count == max then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
if count > max then
  return math.max(1, redis.call('PTTL', KEYS[1]))
end
return 0
