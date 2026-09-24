-- 活动（大聚合写法的根）、场次（小聚合写法的根）与报名
CREATE TABLE event (
  id BIGINT PRIMARY KEY,
  title VARCHAR(64) NOT NULL
);
CREATE TABLE session (
  id BIGINT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  capacity INT NOT NULL,
  confirmed_count INT NOT NULL DEFAULT 0,
  waitlisted_count INT NOT NULL DEFAULT 0,
  KEY idx_event (event_id)
);
-- 拆分写法中候补队列是独立聚合，用这一行作为它的锁
CREATE TABLE waitlist (
  session_id BIGINT PRIMARY KEY
);
CREATE TABLE registration (
  seq BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  attendee VARCHAR(32) NOT NULL,
  status ENUM('CONFIRMED', 'WAITLISTED', 'CANCELLED') NOT NULL,
  UNIQUE KEY uk_session_attendee (session_id, attendee),
  KEY idx_session_status (session_id, status, seq)
);
