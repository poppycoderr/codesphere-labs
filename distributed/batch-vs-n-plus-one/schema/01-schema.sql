-- 订单与用户：演示时放在同一个库，程序按「两个库」的方式分开查询；JOIN 只作为同库时的对照
CREATE TABLE customer (
    id   BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL
);
CREATE TABLE orders (
    id          BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    amount      INT    NOT NULL,
    KEY idx_customer (customer_id)
);
