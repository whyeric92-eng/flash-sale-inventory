-- Give stock back for an order that failed for good, drop its message, and mark it FAILED,
-- all in one step.
-- Two separate calls could crash in between: the message would stay in processing, be retried
-- after restart, fail again, and give the stock back a second time.
-- KEYS[1] = stock key, KEYS[2] = processing list, KEYS[3] = order status key (a hash)
-- ARGV[1] = quantity, ARGV[2] = the message JSON (exact string, as LREM matches whole values)
-- Only add back if the stock key exists. If it is gone, the next order reloads the stock from
-- Postgres, which already has the right number (the failed transaction rolled back). INCRBY on a
-- missing key would instead create it with just `quantity` and hide the real stock.
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
end
redis.call('LREM', KEYS[2], 1, ARGV[2])
-- Same idea: if the status expired, don't create a half entry without the userId.
if redis.call('EXISTS', KEYS[3]) == 1 then
    redis.call('HSET', KEYS[3], 'status', 'FAILED')
end
return 1
