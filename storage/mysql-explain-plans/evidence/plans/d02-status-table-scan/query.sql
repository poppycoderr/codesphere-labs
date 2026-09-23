SELECT * FROM orders IGNORE INDEX (idx_status) WHERE status = 'PAID';
