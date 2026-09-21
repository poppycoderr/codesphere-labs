SELECT /*+ SET_VAR(optimizer_switch = 'prefer_ordering_index=off') */ * FROM task_event
WHERE state = 0 AND event_type IN ('PAY', 'REFUND') AND deleted = 0
ORDER BY id LIMIT 100
