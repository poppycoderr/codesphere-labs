-- 确定性造数：300 万行，每天 1 万行，2025-01-01 起 300 天；biz_id 等于 id，没有索引
SET SESSION cte_max_recursion_depth = 4000000;
INSERT INTO event_log (id, biz_id, content, created_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 3000000)
SELECT n, n, RPAD(CONCAT('event-', n, '-'), 150, 'x'),
       TIMESTAMP('2025-01-01') + INTERVAL ((n - 1) DIV 10000) DAY + INTERVAL ((n - 1) % 10000) * 8 SECOND
FROM seq;
CREATE TABLE event_log_copy LIKE event_log;
INSERT INTO event_log_copy SELECT * FROM event_log;
CREATE TABLE event_log_range LIKE event_log;
INSERT INTO event_log_range SELECT * FROM event_log;
INSERT INTO event_log_p SELECT * FROM event_log;
ANALYZE TABLE event_log, event_log_copy, event_log_range, event_log_p;
