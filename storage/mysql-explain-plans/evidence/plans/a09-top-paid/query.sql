SELECT customer_id, COUNT(*) FROM orders WHERE status = 'PAID' GROUP BY customer_id ORDER BY COUNT(*) DESC LIMIT 5;
