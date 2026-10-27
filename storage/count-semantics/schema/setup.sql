-- 50 万行订单；remark 可为 NULL 且没有索引，coupon_id 可为 NULL 且有索引
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id INT NOT NULL,
  status TINYINT NOT NULL,
  coupon_id INT NULL,
  remark VARCHAR(200) NULL,
  amount INT NOT NULL,
  KEY idx_status (status),
  KEY idx_coupon (coupon_id)
);
SET SESSION cte_max_recursion_depth = 1000000;
INSERT INTO orders (user_id, status, coupon_id, remark, amount)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 500000)
SELECT n % 9973, n % 4, IF(n % 3 = 0, n % 100, NULL), IF(n % 5 = 0, REPEAT('备注', 20), NULL), n % 1000 FROM seq;
ANALYZE TABLE orders;
