-- 确定性造数：全部按主键顺序插入，n 由 digits 表的笛卡尔积计算
INSERT INTO orders
SELECT n, (n - 1) % 5000 + 1, ELT(CRC32(n) % 4 + 1, 'CREATED', 'PAID', 'SHIPPED', 'CLOSED'), ROUND((n * 37) % 100000 / 100 + 1, 2),
       CONCAT('138', LPAD(n, 8, '0')), TIMESTAMP('2026-01-01') + INTERVAL ((n * 7919) % (240 * 86400)) SECOND
FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + 1 AS n
      FROM digits a, digits b, digits c, digits e, digits f) s ORDER BY n;

INSERT INTO orders_big
SELECT n, (n - 1) % 200000 + 1, ELT(CRC32(n) % 4 + 1, 'CREATED', 'PAID', 'SHIPPED', 'CLOSED'), ROUND((n * 37) % 100000 / 100 + 1, 2),
       RPAD(CONCAT('remark-', n, '-'), 120, 'r'), TIMESTAMP('2025-01-01') + INTERVAL ((n * 7919) % (600 * 86400)) SECOND
FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + g.d * 100000 + h.d * 1000000 + 1 AS n
      FROM digits a, digits b, digits c, digits e, digits f, digits g, digits h WHERE h.d < 5) s ORDER BY n;

INSERT INTO narrow_20m
SELECT n, n % 100000, n % 1000
FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + g.d * 100000 + h.d * 1000000 + i.d * 10000000 + 1 AS n
      FROM digits a, digits b, digits c, digits e, digits f, digits g, digits h, digits i WHERE i.d < 2) s ORDER BY n;

INSERT INTO wide_1m
SELECT n, RPAD(CONCAT('payload-', n, '-'), 2000, 'w')
FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + g.d * 100000 + 1 AS n
      FROM digits a, digits b, digits c, digits e, digits f, digits g) s ORDER BY n;

ANALYZE TABLE orders, orders_big, narrow_20m, wide_1m;
