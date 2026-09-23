DROP TABLE IF EXISTS account, coupon, t_noindex, history_probe;
CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL);
INSERT INTO account VALUES (1, 100);
CREATE TABLE coupon (id INT PRIMARY KEY AUTO_INCREMENT, status VARCHAR(16) NOT NULL, KEY idx_status (status));
INSERT INTO coupon (status) VALUES ('USED'), ('USED'), ('USED');
-- 官方文档半一致性读示例的等价表：b 列没有索引
CREATE TABLE t_noindex (a INT NOT NULL, b INT) ENGINE = InnoDB;
INSERT INTO t_noindex VALUES (1, 2), (2, 3), (3, 2), (4, 3), (5, 2);
CREATE TABLE history_probe (id INT PRIMARY KEY, v INT NOT NULL);
