CREATE TABLE inventory (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE REFERENCES product(id),
    stock_quantity INTEGER NOT NULL CHECK (stock_quantity >= 0)
);
