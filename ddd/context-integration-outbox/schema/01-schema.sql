-- 两个上下文各自拥有自己的库：报名上下文写 reg，计费上下文写 billing，彼此不直接读写对方的表
CREATE DATABASE reg;
CREATE DATABASE billing;

CREATE TABLE reg.registration (
  id CHAR(36) PRIMARY KEY,
  session_id VARCHAR(16) NOT NULL,
  attendee_id VARCHAR(32) NOT NULL,
  fee_cents INT NOT NULL,
  status ENUM('CONFIRMED', 'CANCELLED') NOT NULL,
  version INT NOT NULL
);
-- 与业务数据同一事务写入的待发送集成事件
CREATE TABLE reg.outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id CHAR(36) NOT NULL UNIQUE,
  aggregate_id CHAR(36) NOT NULL,
  aggregate_version INT NOT NULL,
  type VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  status ENUM('PENDING', 'SENT', 'FAILED') NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  KEY idx_pending (status, id)
);

CREATE TABLE billing.receivable (
  registration_id CHAR(36) PRIMARY KEY,
  payer_id VARCHAR(32) NOT NULL,
  amount_cents INT NOT NULL,
  currency CHAR(3) NOT NULL,
  status ENUM('OPEN', 'VOID') NOT NULL,
  source_version INT NOT NULL
);
-- 已处理过的事件，用于去重
CREATE TABLE billing.inbox (
  event_id CHAR(36) PRIMARY KEY
);
CREATE TABLE billing.dead_letter (
  event_id CHAR(36) PRIMARY KEY,
  type VARCHAR(64) NOT NULL,
  error VARCHAR(255) NOT NULL
);
-- 不去重、不判断版本的消费端：每收到一次确认就记一笔
CREATE TABLE billing.receivable_log (
  seq BIGINT AUTO_INCREMENT PRIMARY KEY,
  registration_id CHAR(36) NOT NULL,
  payer_id VARCHAR(32) NOT NULL,
  amount_cents INT NOT NULL,
  currency CHAR(3) NOT NULL,
  status ENUM('OPEN', 'VOID') NOT NULL,
  source_version INT NOT NULL,
  KEY idx_registration (registration_id)
);
