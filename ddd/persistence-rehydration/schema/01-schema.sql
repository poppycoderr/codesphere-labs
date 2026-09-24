-- 场次聚合：根表 + 参会人子表
CREATE TABLE session (
  id VARCHAR(16) PRIMARY KEY,
  capacity INT NOT NULL,
  version BIGINT NOT NULL
);
CREATE TABLE session_attendee (
  session_id VARCHAR(16) NOT NULL,
  attendee_id VARCHAR(32) NOT NULL,
  phone CHAR(11) NOT NULL,
  status ENUM('CONFIRMED', 'WAITLISTED') NOT NULL,
  position INT NOT NULL,
  PRIMARY KEY (session_id, attendee_id)
);

-- 票种继承体系的三种映射。票种：免费（无价格）、付费（价格）、团体（价格 + 最少人数）
-- 1. 单表：所有子类型共用一张表，用 CHECK 约束补回每个子类型的必填列
CREATE TABLE st_ticket (
  id BIGINT PRIMARY KEY,
  session_id INT NOT NULL,
  type ENUM('FREE', 'PAID', 'GROUP') NOT NULL,
  name VARCHAR(32) NOT NULL,
  price_cents INT NULL,
  min_size INT NULL,
  KEY idx_session (session_id),
  KEY idx_type_price (type, price_cents),
  CONSTRAINT chk_subtype CHECK (
    (type = 'FREE' AND price_cents IS NULL AND min_size IS NULL) OR
    (type = 'PAID' AND price_cents IS NOT NULL AND min_size IS NULL) OR
    (type = 'GROUP' AND price_cents IS NOT NULL AND min_size IS NOT NULL))
);
-- 2. 每个具体类一张表
CREATE TABLE ct_free_ticket (
  id BIGINT PRIMARY KEY, session_id INT NOT NULL, name VARCHAR(32) NOT NULL, KEY idx_session (session_id));
CREATE TABLE ct_paid_ticket (
  id BIGINT PRIMARY KEY, session_id INT NOT NULL, name VARCHAR(32) NOT NULL, price_cents INT NOT NULL,
  KEY idx_session (session_id), KEY idx_price (price_cents));
CREATE TABLE ct_group_ticket (
  id BIGINT PRIMARY KEY, session_id INT NOT NULL, name VARCHAR(32) NOT NULL, price_cents INT NOT NULL, min_size INT NOT NULL,
  KEY idx_session (session_id));
-- 3. 父表 + 子表（按主键关联）
CREATE TABLE jt_ticket (
  id BIGINT PRIMARY KEY, session_id INT NOT NULL, type ENUM('FREE', 'PAID', 'GROUP') NOT NULL, name VARCHAR(32) NOT NULL,
  KEY idx_session (session_id));
CREATE TABLE jt_paid_detail (
  ticket_id BIGINT PRIMARY KEY, price_cents INT NOT NULL, KEY idx_price (price_cents),
  CONSTRAINT fk_paid FOREIGN KEY (ticket_id) REFERENCES jt_ticket (id));
CREATE TABLE jt_group_detail (
  ticket_id BIGINT PRIMARY KEY, price_cents INT NOT NULL, min_size INT NOT NULL,
  CONSTRAINT fk_group FOREIGN KEY (ticket_id) REFERENCES jt_ticket (id));
