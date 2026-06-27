local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = 1

local meta = redis.call('hmget', key, 'tokens', 'last_refill_time')
local tokens = tonumber(meta[1])
local last_refill_time = tonumber(meta[2])

if tokens == nil then
    tokens = capacity
    last_refill_time = now
else
    local delta = math.max(0, now - last_refill_time)
    local generated = delta * refillRate
    tokens = math.min(tokens + generated, capacity)
    last_refill_time = now
end

if tokens >= requested then
    tokens = tokens - requested
    redis.call('hmset', key, 'tokens', tokens, 'last_refill_time', last_refill_time)
    redis.call('expire', key, 60)
    return 1
else
    redis.call('hmset', key, 'tokens', tokens, 'last_refill_time', last_refill_time)
    return 0
end