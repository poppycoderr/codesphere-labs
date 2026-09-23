DROP TABLE IF EXISTS digits, orders, orders_big, narrow_20m, wide_1m;
CREATE TABLE digits (d INT PRIMARY KEY);
INSERT INTO digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
-- 与 storage/mysql-explain-plans 相同的订单表结构
CREATE TABLE orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id INT NOT NULL, status VARCHAR(16) NOT NULL,
  amount DECIMAL(10,2) NOT NULL, phone VARCHAR(20) NOT NULL, created_at DATETIME NOT NULL
);
-- 带 120 字节备注的订单表
CREATE TABLE orders_big (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id INT NOT NULL, status VARCHAR(16) NOT NULL,
  amount DECIMAL(10,2) NOT NULL, remark VARCHAR(120) NOT NULL, created_at DATETIME NOT NULL
);
-- 窄表：BIGINT 主键加两个 INT
CREATE TABLE narrow_20m (id BIGINT PRIMARY KEY, k INT NOT NULL, v INT NOT NULL);
-- 宽表：每行约 2KB
CREATE TABLE wide_1m (id BIGINT PRIMARY KEY, payload VARCHAR(2000) NOT NULL);
