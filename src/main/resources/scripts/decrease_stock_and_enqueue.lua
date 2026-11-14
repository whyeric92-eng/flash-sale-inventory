-- Atomically check and decrease stock, push the order message onto the queue, and record the
-- order as QUEUED. Redis runs the whole script without interleaving other commands, so either
-- all of it happens, or none of it does (when stock is short or missing).
-- KEYS[1] = stock key, KEYS[2] = queue key, KEYS[3] = order status key (a hash)
-- ARGV[1] = quantity, ARGV[2] = order message (JSON), ARGV[3] = user id, ARGV[4] = status TTL (seconds)
-- Returns 1 = decreased and queued, 0 = not enough stock (unchanged), -1 = key missing
local stock = tonumber(redis.call('GET', KEYS[1]))  -- GET on a missing key gives false; tonumber(false) is nil
if stock == nil then
    return -1
end
local quantity = tonumber(ARGV[1])
if stock < quantity then
    return 0
end
redis.call('DECRBY', KEYS[1], quantity)
-- LPUSH here, consumer takes from the right: first in, first out.
redis.call('LPUSH', KEYS[2], ARGV[2])
-- userId is kept so only the buyer can see this status. The TTL keeps old entries from piling up;
-- once the order is in the database, the database answers status queries instead.
redis.call('HSET', KEYS[3], 'status', 'QUEUED', 'userId', ARGV[3])
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[4]))
return 1
