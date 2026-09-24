-- 幂等记录：一个键一行。token 在每次接管时加一，作为 fencing token；lease_until 是执行者的租约
CREATE TABLE idempotency_keys (
  idem_key     VARCHAR(64)  PRIMARY KEY,
  request_hash CHAR(64)     NOT NULL,
  status       VARCHAR(16)  NOT NULL,
  response     VARCHAR(255) NULL,
  lease_until  TIMESTAMP(3) NOT NULL,
  token        BIGINT       NOT NULL
);

-- 业务表故意不加唯一约束，用来观察重复执行
CREATE TABLE registrations (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  idem_key   VARCHAR(64) NOT NULL,
  user_id    VARCHAR(32) NOT NULL,
  activity   VARCHAR(32) NOT NULL,
  executor   VARCHAR(16) NOT NULL,
  KEY idx_key (idem_key)
);
