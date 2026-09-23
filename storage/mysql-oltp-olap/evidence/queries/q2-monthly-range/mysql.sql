SELECT DATE_FORMAT(created_at, '%Y-%m') AS m, SUM(amount) FROM orders_big WHERE created_at >= '2025-06-01' AND created_at < '2026-01-01' GROUP BY m ORDER BY m
