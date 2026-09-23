DROP TABLE IF EXISTS event_log, event_log_copy, event_log_range, event_log_p;
CREATE TABLE event_log (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  biz_id     BIGINT       NOT NULL,
  content    VARCHAR(200) NOT NULL,
  created_at DATETIME     NOT NULL,
  KEY idx_created (created_at)
);
CREATE TABLE event_log_p (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  biz_id     BIGINT NOT NULL,
  content    VARCHAR(200) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id, created_at),
  KEY idx_created (created_at)
)
PARTITION BY RANGE COLUMNS (created_at) (
  PARTITION p2025q1 VALUES LESS THAN ('2025-04-11'),
  PARTITION p2025q2 VALUES LESS THAN ('2025-07-20'),
  PARTITION pmax    VALUES LESS THAN (MAXVALUE)
);
