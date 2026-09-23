DROP PROCEDURE IF EXISTS batch_delete;
DROP PROCEDURE IF EXISTS range_delete;
DELIMITER //
-- 按条件 LIMIT 分批删除，每条 DELETE 自动提交，每批耗时记在调用方会话的临时表 batch_log 中（临时表不写入行格式 binlog）
CREATE PROCEDURE batch_delete(IN cutoff DATETIME, IN batch_size INT)
BEGIN
  DECLARE n INT DEFAULT 1;
  DECLARE affected INT DEFAULT 1;
  DECLARE t0 DATETIME(6);
  WHILE affected > 0 DO
    SET t0 = NOW(6);
    DELETE FROM event_log_copy WHERE created_at < cutoff ORDER BY created_at LIMIT batch_size;
    SET affected = ROW_COUNT();
    INSERT INTO batch_log VALUES (n, affected, TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)));
    SET n = n + 1;
  END WHILE;
END //
-- 按主键区间分批删除：区间由 MIN(id)、MAX(id) 决定
CREATE PROCEDURE range_delete(IN cutoff DATETIME, IN batch_size INT)
BEGIN
  DECLARE lo BIGINT; DECLARE hi BIGINT; DECLARE n INT DEFAULT 1; DECLARE t0 DATETIME(6);
  SELECT MIN(id), MAX(id) INTO lo, hi FROM event_log_range WHERE created_at < cutoff;
  WHILE lo <= hi DO
    SET t0 = NOW(6);
    DELETE FROM event_log_range WHERE id >= lo AND id < lo + batch_size AND created_at < cutoff;
    INSERT INTO batch_log VALUES (n, ROW_COUNT(), TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)));
    SET lo = lo + batch_size; SET n = n + 1;
  END WHILE;
END //
DELIMITER ;
