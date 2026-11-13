-- Public order number, created when the request arrives (before the row exists).
-- Also makes queue processing idempotent: a second insert of the same order fails on UNIQUE.

-- 1. Add as nullable first, so existing rows don't break the NOT NULL check.
ALTER TABLE orders ADD COLUMN order_no UUID;

-- 2. Give existing rows a value.
UPDATE orders SET order_no = gen_random_uuid() WHERE order_no IS NULL;

-- 3. Now every row has one: make it required and unique.
ALTER TABLE orders ALTER COLUMN order_no SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT uq_orders_order_no UNIQUE (order_no);
