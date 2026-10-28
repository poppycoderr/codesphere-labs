-- 账户表与 Seata AT 模式要求的 undo_log 表
CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL);
CREATE TABLE undo_log (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL,
  log_created DATETIME(6) NOT NULL,
  log_modified DATETIME(6) NOT NULL,
  UNIQUE KEY ux_undo_log (xid, branch_id)
);
