-- Atomically check and decrease stock, then push the order message onto the queue.
-- Redis runs the whole script without interleaving other commands, so either both
-- the decrease and the push happen, or neither does (when stock is short or missing).
-- KEYS[1] = stock key, KEYS[2] = queue key
-- ARGV[1] = quantity, ARGV[2] = order message (JSON)
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
return 1
