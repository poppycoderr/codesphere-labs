-- 合成数据：5 个用户、5 笔订单（其中 1 笔是没有用户的游客订单）、8 行订单明细、5 笔支付。
-- 手算的正确答案：订单金额合计 480，明细小计合计 480，已支付合计 430。
DROP TABLE IF EXISTS payments, order_items, orders, users;
CREATE TABLE users (
  id   INT PRIMARY KEY,
  city VARCHAR(10) NOT NULL
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
CREATE TABLE payments (
  id       INT PRIMARY KEY AUTO_INCREMENT,
  order_id INT NOT NULL,
  amount   DECIMAL(10,2) NOT NULL,
  KEY idx_order (order_id)
);
INSERT INTO users VALUES (1,'X'),(2,'X'),(3,'Y'),(4,'Y'),(5,'Y');
INSERT INTO orders VALUES (1,1,100,'PAID'),(2,1,200,'PAID'),(3,2,100,'PAID'),(4,3,50,'CREATED'),(5,NULL,30,'PAID');
INSERT INTO order_items (order_id, sku, qty, price) VALUES
  (1,'A',1,60),(1,'B',1,40),
  (2,'A',1,60),(2,'C',1,140),
  (3,'B',1,40),(3,'B',1,60),
  (4,'D',1,50),
  (5,'E',1,30);
INSERT INTO payments (order_id, amount) VALUES (1,100),(2,150),(2,50),(3,100),(5,30);
