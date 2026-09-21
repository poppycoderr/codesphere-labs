DROP TABLE IF EXISTS task_event;
CREATE TABLE task_event (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  state      TINYINT      NOT NULL,          -- 0 待处理，2 已处理
  event_type VARCHAR(16)  NOT NULL,
  deleted    TINYINT      NOT NULL DEFAULT 0,
  payload    VARCHAR(200) NOT NULL,
  gmt_create DATETIME     NOT NULL,
  KEY idx_state_event_deleted (state, event_type, deleted)
) ENGINE = InnoDB;
