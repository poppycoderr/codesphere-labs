-- 合成数据：与 storage/sql-result-grain 相同的小数据集（手算答案：订单金额合计 480），外加一张 10 万行的事件表用于成本检查。
DROP VIEW IF EXISTS v_users, v_users_invoker;
DROP TABLE IF EXISTS payments, order_items, orders, users, events;
CREATE TABLE users (
  id    INT PRIMARY KEY,
  city  VARCHAR(10) NOT NULL,
  phone VARCHAR(20) NOT NULL
);
CREATE TABLE orders (
  id      INT PRIMARY KEY,
  user_id INT NULL,
  amount  DECIMAL(10,2) NOT NULL,
  status  VARCHAR(10) NOT NULL
);
CREATE TABLE order_items (
  id       INT PRIMARY KEY AUTO_INCREMENT,
  order_id INT NOT NULL,
  sku      VARCHAR(10) NOT NULL,
  qty      INT NOT NULL,
  price    DECIMAL(10,2) NOT NULL,
  KEY idx_order (order_id)
);
CREATE TABLE events (
  id      INT PRIMARY KEY AUTO_INCREMENT,
  user_id INT NOT NULL,
  kind    VARCHAR(10) NOT NULL,
  KEY idx_user (user_id)
);
INSERT INTO users VALUES (1,'X','13800000001'),(2,'X','13800000002'),(3,'Y','13800000003'),(4,'Y','13800000004'),(5,'Y','13800000005');
INSERT INTO orders VALUES (1,1,100,'PAID'),(2,1,200,'PAID'),(3,2,100,'PAID'),(4,3,50,'CREATED'),(5,NULL,30,'PAID');
INSERT INTO order_items (order_id, sku, qty, price) VALUES (1,'A',1,60),(1,'B',1,40),(2,'A',1,60),(2,'C',1,140),(3,'B',1,40),(3,'B',1,60),(4,'D',1,50),(5,'E',1,30);
INSERT INTO events (user_id, kind)
  WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100000)
  SELECT /*+ SET_VAR(cte_max_recursion_depth = 200000) */ 1 + n % 5, IF(n % 3 = 0, 'click', 'view') FROM seq;
ANALYZE TABLE events, orders, order_items, users;

-- 视图：不暴露 phone 列
CREATE SQL SECURITY DEFINER VIEW v_users AS SELECT id, city FROM users;
CREATE SQL SECURITY INVOKER VIEW v_users_invoker AS SELECT id, city FROM users;

-- 账号：应用账号有读写权限；查询账号只能读指定的表和视图
DROP USER IF EXISTS 'app_rw'@'%', 'report_ro'@'%';
CREATE USER 'app_rw'@'%' IDENTIFIED BY 'example_password';
GRANT SELECT, INSERT, UPDATE, DELETE ON labs.* TO 'app_rw'@'%';
CREATE USER 'report_ro'@'%' IDENTIFIED BY 'example_password';
GRANT SELECT ON labs.orders TO 'report_ro'@'%';
GRANT SELECT ON labs.order_items TO 'report_ro'@'%';
GRANT SELECT ON labs.events TO 'report_ro'@'%';
GRANT SELECT ON labs.v_users TO 'report_ro'@'%';
GRANT SELECT ON labs.v_users_invoker TO 'report_ro'@'%';
