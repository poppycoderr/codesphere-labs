DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  customer_id INT           NOT NULL,
  status      VARCHAR(16)   NOT NULL,
  amount      DECIMAL(10,2) NOT NULL,
  phone       VARCHAR(20)   NOT NULL,
  created_at  DATETIME      NOT NULL,
  KEY idx_customer_created (customer_id, created_at),
  KEY idx_phone (phone)
);
