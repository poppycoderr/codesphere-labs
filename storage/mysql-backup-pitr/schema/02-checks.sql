-- 业务校验：只用业务字段（不含自增 id 与时间），同一份业务历史在任何实例上得到相同结果
USE shop;
SELECT 'orders' AS item, COUNT(*) AS n, COALESCE(SUM(CRC32(CONCAT_WS(',', id, request_id, customer_id, status, total_amount))), 0) AS checksum FROM orders
UNION ALL SELECT 'order_items', COUNT(*), COALESCE(SUM(CRC32(CONCAT_WS(',', order_id, sku_id, quantity, unit_price))), 0) FROM order_items
UNION ALL SELECT 'inventory', COUNT(*), COALESCE(SUM(CRC32(CONCAT_WS(',', sku_id, available, version))), 0) FROM inventory
UNION ALL SELECT 'operation_markers', COUNT(*), COALESCE(SUM(CRC32(CONCAT_WS(',', sequence, request_id, operation))), 0) FROM operation_markers
UNION ALL SELECT 'invariant_order_total_mismatch', COUNT(*), 0 FROM orders o
  WHERE o.total_amount <> (SELECT COALESCE(SUM(quantity * unit_price), 0) FROM order_items i WHERE i.order_id = o.id)
UNION ALL SELECT 'invariant_inventory_mismatch', COUNT(*), 0 FROM inventory v
  WHERE v.available <> 1000000 - (SELECT COALESCE(SUM(quantity), 0) FROM order_items i WHERE i.sku_id = v.sku_id)
UNION ALL SELECT 'invariant_marker_gap', (SELECT COALESCE(MAX(sequence), 0) - COUNT(*) FROM operation_markers), 0
UNION ALL SELECT 'invariant_orphan_items', COUNT(*), 0 FROM order_items i LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL;
