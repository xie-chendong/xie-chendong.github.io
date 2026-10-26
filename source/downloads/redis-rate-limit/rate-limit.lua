local key = KEYS[1]
local startTs = tonumber(ARGV[1])
local endTs = tonumber(ARGV[2])
local maxCount = tonumber(ARGV[3])
local seconds = tonumber(ARGV[4])
local uuid = ARGV[5]
redis.call('ZREMRANGEBYSCORE', key, 0, startTs)
local count = redis.call('ZCARD', key)
if count >= maxCount then
return 1
end
redis.call('ZADD', key, endTs, uuid)
redis.call('EXPIRE', key, seconds)
return 0
