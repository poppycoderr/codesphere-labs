-- 命令侧：场次聚合（根表 + 报名子表）
CREATE TABLE session (
  id INT PRIMARY KEY,
  event_id INT NOT NULL,
  capacity INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  KEY idx_event (event_id)
);
CREATE TABLE registration (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id INT NOT NULL,
  attendee_id VARCHAR(32) NOT NULL,
  status ENUM('CONFIRMED', 'WAITLISTED') NOT NULL,
  paid_cents INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_session_attendee (session_id, attendee_id),
  KEY idx_session_status (session_id, status)
);
-- 命令侧产生的事实，供异步投影与重放使用
CREATE TABLE domain_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id INT NOT NULL,
  type ENUM('CONFIRMED', 'WAITLISTED', 'PAID') NOT NULL,
  amount_cents INT NOT NULL DEFAULT 0
);
-- 读模型一：与命令同一事务维护
CREATE TABLE session_stats (
  session_id INT PRIMARY KEY,
  event_id INT NOT NULL,
  confirmed INT NOT NULL,
  waitlisted INT NOT NULL,
  paid_cents BIGINT NOT NULL,
  KEY idx_event (event_id)
);
-- 读模型二：由投影器异步维护，checkpoint 与投影结果同一事务提交
CREATE TABLE session_stats_async LIKE session_stats;
CREATE TABLE projection_checkpoint (
  name VARCHAR(32) PRIMARY KEY,
  last_event_id BIGINT NOT NULL
);
