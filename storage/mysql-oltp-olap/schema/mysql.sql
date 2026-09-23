-- 确定性造数：500 万行，20 万个客户，时间跨度 600 天，含 120 字节备注；与 clickhouse.sql 的公式相同
DROP TABLE IF EXISTS digits, orders_big;
CREATE TABLE digits (d INT PRIMARY KEY);
INSERT INTO digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TABLE orders_big (
  id BIGINT PRIMARY KEY, customer_id INT NOT NULL, status VARCHAR(16) NOT NULL, amount DECIMAL(10,2) NOT NULL,
  remark VARCHAR(120) NOT NULL, created_at DATETIME NOT NULL,
  KEY idx_customer_created (customer_id, created_at)
);
INSERT INTO orders_big
SELECT n, (n * 7919) % 200000 + 1, ELT(CRC32(n) % 4 + 1, 'CREATED', 'PAID', 'SHIPPED', 'CLOSED'), ROUND((n * 37) % 100000 / 100 + 1, 2),
       RPAD(CONCAT('remark-', n % 1000, '-'), 120, 'r'), TIMESTAMP('2025-01-01') + INTERVAL ((n * 104729) % (600 * 86400)) SECOND
FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + g.d * 100000 + h.d * 1000000 + 1 AS n
      FROM digits a, digits b, digits c, digits e, digits f, digits g, digits h WHERE h.d < 5) s ORDER BY n;
ANALYZE TABLE orders_big;
