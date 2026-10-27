-- 2 万客户、30 万订单；orders.customer_id 有索引，customer_ref 存同样的值但没有索引；customers.code 与 id 相同但没有索引
DROP TABLE IF EXISTS orders; DROP TABLE IF EXISTS customers;
CREATE TABLE customers (
  id INT PRIMARY KEY,
  code INT NOT NULL,
  city TINYINT NOT NULL,
  name VARCHAR(60) NOT NULL
);
CREATE TABLE orders (
  id INT PRIMARY KEY,
  customer_id INT NOT NULL,
  customer_ref INT NOT NULL,
  amount INT NOT NULL,
  KEY idx_customer (customer_id)
);
SET SESSION cte_max_recursion_depth = 1000000;
INSERT INTO customers (id, code, city, name)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 20000)
SELECT n, n, n % 100, CONCAT('customer-', n) FROM seq;
INSERT INTO orders (id, customer_id, customer_ref, amount)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 300000)
SELECT n, (n * 7) % 20000 + 1, (n * 7) % 20000 + 1, n % 1000 FROM seq;
ANALYZE TABLE customers, orders;
