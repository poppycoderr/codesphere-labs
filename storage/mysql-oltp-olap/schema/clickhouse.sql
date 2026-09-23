DROP TABLE IF EXISTS orders_big;
CREATE TABLE orders_big (
  id UInt64, customer_id UInt32, status String, amount Decimal(10, 2), remark String, created_at DateTime('UTC')
) ENGINE = MergeTree ORDER BY (created_at, customer_id);
INSERT INTO orders_big
SELECT n, (n * 7919) % 200000 + 1, ['CREATED', 'PAID', 'SHIPPED', 'CLOSED'][CRC32(toString(n)) % 4 + 1],
       CAST((n * 37) % 100000 + 100 AS Decimal64(2)) / 100,
       rightPad(concat('remark-', toString(n % 1000), '-'), 120, 'r'),
       toDateTime('2025-01-01 00:00:00', 'UTC') + toIntervalSecond((n * 104729) % (600 * 86400))
FROM (SELECT number + 1 AS n FROM numbers(5000000));
OPTIMIZE TABLE orders_big FINAL;
