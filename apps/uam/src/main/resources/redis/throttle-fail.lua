-- 登录失败计数：自增并刷新 TTL（连续失败的窗口 = 封锁时长），同一脚本内完成，不会留下没有 TTL 的永久计数。
-- KEYS[1] uam:throttle:login:{subject}    ARGV: 1 block(ms)
local count = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return count
