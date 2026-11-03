-- Atomically check and decrease stock. Redis runs the whole script without
-- interleaving other commands, so no two callers can both pass the check.
-- KEYS[1] = stock key, ARGV[1] = quantity
-- Returns 1 = decreased, 0 = not enough stock (unchanged), -1 = key missing
local stock = tonumber(redis.call('GET', KEYS[1]))  -- GET on a missing key gives false; tonumber(false) is nil
if stock == nil then
    return -1
end
local quantity = tonumber(ARGV[1])
if stock < quantity then
    return 0
end
redis.call('DECRBY', KEYS[1], quantity)
return 1
