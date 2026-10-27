-- 一张小表即可：元数据锁与数据量无关
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (id INT PRIMARY KEY, amount INT NOT NULL);
INSERT INTO orders VALUES (1, 100), (2, 200), (3, 300);
