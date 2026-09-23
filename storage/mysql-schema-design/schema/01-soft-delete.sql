DROP TABLE IF EXISTS service_record_a, service_record_b, service_record_c;
-- 错误写法：唯一索引包含可空的 deleted_at
CREATE TABLE service_record_a (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, product_code VARCHAR(32) NOT NULL,
  deleted_at DATETIME NULL,
  UNIQUE KEY uk_user_product (user_id, product_code, deleted_at)
);
INSERT INTO service_record_a (user_id, product_code) VALUES (1001, 'VIP');
INSERT INTO service_record_a (user_id, product_code) VALUES (1001, 'VIP');
SELECT 'A: two active rows allowed?' AS test, COUNT(*) AS active_rows FROM service_record_a WHERE deleted_at IS NULL;

-- 写法一：deleted_id，0 表示未删除，删除时改成自身主键
CREATE TABLE service_record_b (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, product_code VARCHAR(32) NOT NULL,
  deleted_id BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_product (user_id, product_code, deleted_id)
);
INSERT INTO service_record_b (user_id, product_code) VALUES (1001, 'VIP');
UPDATE service_record_b SET deleted_id = id WHERE user_id = 1001 AND product_code = 'VIP' AND deleted_id = 0;
INSERT INTO service_record_b (user_id, product_code) VALUES (1001, 'VIP');
UPDATE service_record_b SET deleted_id = id WHERE user_id = 1001 AND product_code = 'VIP' AND deleted_id = 0;
INSERT INTO service_record_b (user_id, product_code) VALUES (1001, 'VIP');
SELECT id, deleted_id FROM service_record_b ORDER BY id;

-- 写法二：函数索引只约束未删除的行
CREATE TABLE service_record_c (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, product_code VARCHAR(32) NOT NULL,
  deleted_at DATETIME NULL,
  UNIQUE KEY uk_active ((IF(deleted_at IS NULL, CONCAT(user_id, ':', product_code), NULL)))
);
INSERT INTO service_record_c (user_id, product_code) VALUES (1001, 'VIP');
UPDATE service_record_c SET deleted_at = '2026-01-01 00:00:00' WHERE id = 1;
INSERT INTO service_record_c (user_id, product_code) VALUES (1001, 'VIP');
UPDATE service_record_c SET deleted_at = '2026-01-01 00:00:00' WHERE id = 2;
INSERT INTO service_record_c (user_id, product_code) VALUES (1001, 'VIP');
SELECT id, deleted_at FROM service_record_c ORDER BY id;
