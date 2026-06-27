local stock = redis.call('get', KEYS[1])
if not stock then return -1 end

stock = tonumber(stock)
local amount = tonumber(ARGV[1])

if stock >= amount then 
    return redis.call('decrby', KEYS[1], amount)
else
    return -2
end