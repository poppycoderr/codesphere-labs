SELECT * FROM task_event FORCE INDEX (idx_state_event_deleted)
WHERE state = 0 AND event_type IN ('PAY', 'REFUND') AND deleted = 0
ORDER BY id LIMIT 100
