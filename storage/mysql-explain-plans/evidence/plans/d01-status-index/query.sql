SELECT * FROM orders FORCE INDEX (idx_status) WHERE status = 'PAID';
