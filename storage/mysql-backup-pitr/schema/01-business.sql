-- 小型订单模型：金额用整数分，全部为虚构数据
CREATE DATABASE IF NOT EXISTS shop;
USE shop;
CREATE TABLE inventory (sku_id INT PRIMARY KEY, available BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0);
CREATE TABLE orders (
  id BIGINT PRIMARY KEY, request_id VARCHAR(40) NOT NULL, customer_id INT NOT NULL, status VARCHAR(16) NOT NULL,
  total_amount BIGINT NOT NULL, created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_request (request_id)
);
CREATE TABLE order_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, sku_id INT NOT NULL, quantity INT NOT NULL, unit_price BIGINT NOT NULL,
  KEY idx_order (order_id), CONSTRAINT fk_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
CREATE TABLE operation_markers (
  sequence BIGINT PRIMARY KEY, request_id VARCHAR(40) NOT NULL, operation VARCHAR(16) NOT NULL, committed_at DATETIME(6) NOT NULL
);
INSERT INTO inventory (sku_id, available)
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 100) SELECT n, 1000000 FROM s;

DELIMITER //
-- 一笔下单是一个事务：订单、3 条明细、扣库存、操作标记；全部数值由订单号 n 确定
CREATE PROCEDURE place_order(IN n BIGINT)
BEGIN
  DECLARE i INT DEFAULT 1; DECLARE total BIGINT DEFAULT 0;
  START TRANSACTION;
  INSERT INTO orders VALUES (n, CONCAT('order-', n), n % 97 + 1, 'PAID', 0, NOW(6));
  WHILE i <= 3 DO
    INSERT INTO order_items (order_id, sku_id, quantity, unit_price) VALUES (n, (n * 7 + i * 13) % 100 + 1, (n + i) % 5 + 1, 100 + (n * i) % 900);
    UPDATE inventory SET available = available - ((n + i) % 5 + 1), version = version + 1 WHERE sku_id = (n * 7 + i * 13) % 100 + 1;
    SET total = total + ((n + i) % 5 + 1) * (100 + (n * i) % 900);
    SET i = i + 1;
  END WHILE;
  UPDATE orders SET total_amount = total WHERE id = n;
  INSERT INTO operation_markers VALUES (n, CONCAT('order-', n), 'place', NOW(6));
  COMMIT;
END //
CREATE PROCEDURE place_orders(IN first_n BIGINT, IN last_n BIGINT, IN pause_seconds DECIMAL(6, 3))
BEGIN
  DECLARE n BIGINT DEFAULT first_n;
  WHILE n <= last_n DO CALL place_order(n); IF pause_seconds > 0 THEN DO SLEEP(pause_seconds); END IF; SET n = n + 1; END WHILE;
END //
DELIMITER ;
