-- 20 万行商品：category 只有 5 个值，price 只有 50 个值（大量同值），score 与 name 用于排序与回表
DROP TABLE IF EXISTS products;
CREATE TABLE products (
  id INT PRIMARY KEY,
  category TINYINT NOT NULL,
  score INT NOT NULL,
  price INT NOT NULL,
  name VARCHAR(100) NOT NULL,
  KEY idx_cat_score (category, score),
  KEY idx_cat_score_desc (category, score DESC)
);
SET SESSION cte_max_recursion_depth = 1000000;
INSERT INTO products (id, category, score, price, name)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200000)
SELECT n, n % 5, (n * 7919) % 100003, (n * 31) % 50, CONCAT('product-', LPAD(n, 8, '0'), REPEAT('x', 80)) FROM seq;
ANALYZE TABLE products;
