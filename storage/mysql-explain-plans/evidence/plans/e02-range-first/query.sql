SELECT * FROM orders FORCE INDEX (idx_created_status) WHERE status = 'PAID' AND created_at >= '2026-06-01' ORDER BY created_at;
