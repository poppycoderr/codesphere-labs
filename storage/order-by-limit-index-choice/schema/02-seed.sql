-- 确定性造数：不使用 RAND()，所有字段由 id 计算，任何机器上生成完全相同的数据
--   行数：3,000,000
--   state：前 95%（id <= 2,850,000）全部为 2；最后 5% 中 id 能被 3 整除的 50,000 行为 0，其余为 2
--   event_type：CRC32(id) % 10 → 0-3 PAY（40%），4 REFUND（10%），5-7 SHIP（30%），8-9 NOTIFY（20%）
--   deleted：CRC32(CONCAT('d', id)) % 50 = 0 时为 1（约 2%）
SET SESSION cte_max_recursion_depth = 1000001;
SET @batch := 0;
DROP PROCEDURE IF EXISTS seed_task_event;
DELIMITER //
CREATE PROCEDURE seed_task_event()
BEGIN
  DECLARE b INT DEFAULT 0;
  WHILE b < 3 DO
    INSERT INTO task_event (id, state, event_type, deleted, payload, gmt_create)
    WITH RECURSIVE seq (n) AS (
      SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000000
    )
    SELECT b * 1000000 + n,
           IF(b * 1000000 + n > 2850000 AND (b * 1000000 + n) % 3 = 0, 0, 2),
           ELT(1 + CRC32(b * 1000000 + n) % 10, 'PAY','PAY','PAY','PAY','REFUND','SHIP','SHIP','SHIP','NOTIFY','NOTIFY'),
           IF(CRC32(CONCAT('d', b * 1000000 + n)) % 50 = 0, 1, 0),
           LPAD(b * 1000000 + n, 120, 'x'),
           TIMESTAMP('2026-01-01') + INTERVAL (b * 1000000 + n) SECOND
    FROM seq;
    COMMIT;
    SET b = b + 1;
  END WHILE;
END //
DELIMITER ;
CALL seed_task_event();
DROP PROCEDURE seed_task_event;
