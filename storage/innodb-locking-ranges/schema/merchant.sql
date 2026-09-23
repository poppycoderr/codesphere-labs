-- 商户订单：2 万行，10 个商户各 2,000 单；id = 1007 的订单属于商户 7，external_no 为 A1007
SET SESSION cte_max_recursion_depth = 30000;
DROP TABLE IF EXISTS orders_m;
CREATE TABLE orders_m (
  id          BIGINT PRIMARY KEY,
  merchant_id INT         NOT NULL,
  external_no VARCHAR(32) NOT NULL,
  status      VARCHAR(16) NOT NULL,
  KEY idx_merchant (merchant_id)
) ENGINE = InnoDB;
INSERT INTO orders_m
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 20000)
SELECT n, (n - 1) % 10 + 1, CONCAT('A', n), 'CREATED' FROM s;
ANALYZE TABLE orders_m;
