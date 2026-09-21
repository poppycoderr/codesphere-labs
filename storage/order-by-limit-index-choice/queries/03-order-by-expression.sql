SELECT * FROM task_event
WHERE state = 0 AND event_type IN ('PAY', 'REFUND') AND deleted = 0
ORDER BY id + 0 LIMIT 100
