ALTER TABLE orders
    ADD COLUMN user_id BIGINT NOT NULL REFERENCES users(id);

CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_product_id ON orders (product_id);
