SELECT * FROM task_event
WHERE state = 2 AND event_type IN ('PAY', 'REFUND') AND deleted = 0
ORDER BY id LIMIT 100
