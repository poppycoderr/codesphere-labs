SELECT status, COUNT(*), SUM(amount) FROM orders_big GROUP BY status ORDER BY status
