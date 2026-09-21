SELECT * FROM task_event
WHERE state = 0 AND event_type = 'PAY' AND deleted = 0
ORDER BY id LIMIT 100
