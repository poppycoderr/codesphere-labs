-- 确定性造数：全部由主键 id 计算，重复执行得到完全相同的数据
--   customer_id：5,000 个客户，每个客户 20 单
--   status：CRC32(id) 取模，CREATED / PAID / SHIPPED / CLOSED 各约 1/4
--   created_at：2026-01-01 起 240 天内，按 id 乘以质数取模打散
--   phone：138 加 8 位 id，例如 id = 42 对应 13800000042
SET SESSION cte_max_recursion_depth = 200000;
INSERT INTO orders (id, customer_id, status, amount, phone, created_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100000)
SELECT n,
       (n - 1) % 5000 + 1,
       ELT(CRC32(n) % 4 + 1, 'CREATED', 'PAID', 'SHIPPED', 'CLOSED'),
       ROUND((n * 37) % 100000 / 100 + 1, 2),
       CONCAT('138', LPAD(n, 8, '0')),
       TIMESTAMP('2026-01-01') + INTERVAL ((n * 7919) % (240 * 86400)) SECOND
FROM seq;
ANALYZE TABLE orders;
