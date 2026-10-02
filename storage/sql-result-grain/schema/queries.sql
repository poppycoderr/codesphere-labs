-- 每条查询输出一行：键、结果。键与 scripts/verify.sh 中手算的期望值一一对应。

-- 0. 基准：单表上的正确答案
SELECT 'truth.order_amount', SUM(amount) FROM orders;
SELECT 'truth.item_subtotal', SUM(qty * price) FROM order_items;
SELECT 'truth.paid', SUM(amount) FROM payments;

-- 1. 订单连明细后求订单金额：每笔订单被它的明细行数放大
SELECT 'fanout.one_child.sum_order_amount', SUM(o.amount) FROM orders o JOIN order_items i ON i.order_id = o.id;
SELECT 'fanout.one_child.rows_vs_orders', CONCAT(COUNT(*), ' 行 / ', COUNT(DISTINCT o.id), ' 笔订单') FROM orders o JOIN order_items i ON i.order_id = o.id;

-- 2. 同时连明细和支付：两个子表互相放大，没有支付的订单被内连接丢掉
SELECT 'fanout.two_children.sum_paid', SUM(p.amount) FROM orders o JOIN order_items i ON i.order_id = o.id JOIN payments p ON p.order_id = o.id;
SELECT 'fanout.two_children.sum_item_subtotal', SUM(i.qty * i.price) FROM orders o JOIN order_items i ON i.order_id = o.id JOIN payments p ON p.order_id = o.id;
SELECT 'fanout.two_children.rows_vs_orders', CONCAT(COUNT(*), ' 行 / ', COUNT(DISTINCT o.id), ' 笔订单') FROM orders o JOIN order_items i ON i.order_id = o.id JOIN payments p ON p.order_id = o.id;

-- 3. 用 DISTINCT 去重：金额相同的不同订单被合并
SELECT 'distinct.sum_distinct_amount', SUM(DISTINCT o.amount) FROM orders o JOIN order_items i ON i.order_id = o.id;

-- 4. 先把每个子表聚合到订单粒度，再连接
SELECT 'preagg.order_amount', SUM(o.amount) FROM orders o
  LEFT JOIN (SELECT order_id, SUM(qty * price) AS subtotal FROM order_items GROUP BY order_id) i ON i.order_id = o.id
  LEFT JOIN (SELECT order_id, SUM(amount) AS paid FROM payments GROUP BY order_id) p ON p.order_id = o.id;
SELECT 'preagg.item_subtotal', SUM(i.subtotal) FROM orders o
  LEFT JOIN (SELECT order_id, SUM(qty * price) AS subtotal FROM order_items GROUP BY order_id) i ON i.order_id = o.id
  LEFT JOIN (SELECT order_id, SUM(amount) AS paid FROM payments GROUP BY order_id) p ON p.order_id = o.id;
SELECT 'preagg.paid', SUM(p.paid) FROM orders o
  LEFT JOIN (SELECT order_id, SUM(qty * price) AS subtotal FROM order_items GROUP BY order_id) i ON i.order_id = o.id
  LEFT JOIN (SELECT order_id, SUM(amount) AS paid FROM payments GROUP BY order_id) p ON p.order_id = o.id;

-- 5. 四种「计数」数的不是同一个东西
SELECT 'count.star', COUNT(*) FROM orders o LEFT JOIN payments p ON p.order_id = o.id;
SELECT 'count.payment_id', COUNT(p.id) FROM orders o LEFT JOIN payments p ON p.order_id = o.id;
SELECT 'count.distinct_order', COUNT(DISTINCT o.id) FROM orders o LEFT JOIN payments p ON p.order_id = o.id;
SELECT 'count.distinct_paid_order', COUNT(DISTINCT p.order_id) FROM orders o LEFT JOIN payments p ON p.order_id = o.id;
SELECT 'count.item_rows_sku_b', COUNT(*) FROM order_items WHERE sku = 'B';
SELECT 'count.orders_with_sku_b', COUNT(DISTINCT order_id) FROM order_items WHERE sku = 'B';

-- 6. 外连接之后在 WHERE 里过滤右表：没有匹配的用户被丢掉
SELECT 'outer.where.user_rows', COUNT(*) FROM (
  SELECT u.id, SUM(o.amount) AS paid FROM users u LEFT JOIN orders o ON o.user_id = u.id WHERE o.status = 'PAID' GROUP BY u.id) t;
SELECT 'outer.on.user_rows', COUNT(*) FROM (
  SELECT u.id, COALESCE(SUM(o.amount), 0) AS paid FROM users u LEFT JOIN orders o ON o.user_id = u.id AND o.status = 'PAID' GROUP BY u.id) t;
SELECT 'outer.on.detail', GROUP_CONCAT(CONCAT('u', id, '=', paid) ORDER BY id SEPARATOR ' ') FROM (
  SELECT u.id, CAST(COALESCE(SUM(o.amount), 0) AS SIGNED) AS paid FROM users u LEFT JOIN orders o ON o.user_id = u.id AND o.status = 'PAID' GROUP BY u.id) t;

-- 7. 三种「人均订单金额」
SELECT 'avg.per_order_row', AVG(o.amount) FROM users u JOIN orders o ON o.user_id = u.id;
SELECT 'avg.per_user_with_orders', AVG(total) FROM (SELECT u.id, SUM(o.amount) AS total FROM users u JOIN orders o ON o.user_id = u.id GROUP BY u.id) t;
SELECT 'avg.per_all_users', AVG(total) FROM (SELECT u.id, COALESCE(SUM(o.amount), 0) AS total FROM users u LEFT JOIN orders o ON o.user_id = u.id GROUP BY u.id) t;

-- 8. 找没有下过单的用户：子查询结果里有 NULL 时 NOT IN 返回空
SELECT 'notin.with_null', COUNT(*) FROM users WHERE id NOT IN (SELECT user_id FROM orders);
SELECT 'notin.filtered_null', COUNT(*) FROM users WHERE id NOT IN (SELECT user_id FROM orders WHERE user_id IS NOT NULL);
SELECT 'notin.not_exists', COUNT(*) FROM users u WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id);

-- 9. 空集与 NULL：SUM 在没有行时是 NULL，COUNT 是 0；AVG 忽略 NULL
SELECT 'empty.sum', IFNULL(SUM(amount), 'NULL') FROM orders WHERE status = 'REFUNDED';
SELECT 'empty.count', COUNT(*) FROM orders WHERE status = 'REFUNDED';
SELECT 'empty.ratio_null_safe', IFNULL(SUM(amount) / NULLIF(COUNT(*), 0), 'NULL') FROM orders WHERE status = 'REFUNDED';
