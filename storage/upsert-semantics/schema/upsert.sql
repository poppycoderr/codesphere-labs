-- 每一步输出「键<TAB>值」，由 verify.sh 整理成结论行；d 记录上一条语句的影响行数与第一条警告
DROP TABLE IF EXISTS order_items; DROP TABLE IF EXISTS audit; DROP TABLE IF EXISTS users;
CREATE TABLE users (
  id INT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(50) NOT NULL UNIQUE,
  phone VARCHAR(20) NOT NULL UNIQUE,
  nickname VARCHAR(10) NOT NULL,
  points INT NOT NULL DEFAULT 0
);
CREATE TABLE order_items (id INT PRIMARY KEY, user_id INT NOT NULL, FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE);
CREATE TABLE audit (event VARCHAR(20), user_id INT);
CREATE TRIGGER users_ad AFTER DELETE ON users FOR EACH ROW INSERT INTO audit VALUES ('delete', OLD.id);
INSERT INTO users (email, phone, nickname) VALUES ('a@example.com', '100', 'alice'), ('b@example.com', '200', 'bob');
INSERT INTO order_items VALUES (1, 1), (2, 1), (3, 2);

-- 一、INSERT IGNORE：重复、超长、NULL
INSERT IGNORE INTO users (email, phone, nickname) VALUES ('a@example.com', '101', 'dup');
GET DIAGNOSTICS @n = NUMBER, @rc = ROW_COUNT; GET DIAGNOSTICS CONDITION 1 @c = MYSQL_ERRNO;
SELECT 'ignore.dup', CONCAT(@rc, '|', @c);
INSERT IGNORE INTO users (email, phone, nickname) VALUES ('c@example.com', '300', 'a-very-long-nickname');
GET DIAGNOSTICS @n = NUMBER, @rc = ROW_COUNT; GET DIAGNOSTICS CONDITION 1 @c = MYSQL_ERRNO;
SELECT 'ignore.long', CONCAT(@rc, '|', @c, '|', (SELECT nickname FROM users WHERE email = 'c@example.com'));
INSERT IGNORE INTO users (email, phone, nickname) VALUES ('d@example.com', '400', NULL);
GET DIAGNOSTICS @n = NUMBER, @rc = ROW_COUNT; GET DIAGNOSTICS CONDITION 1 @c = MYSQL_ERRNO;
SELECT 'ignore.null', CONCAT(@rc, '|', @c, '|[', (SELECT nickname FROM users WHERE email = 'd@example.com'), ']');
INSERT INTO users (email, phone, nickname) VALUES ('e@example.com', '500', 'a-very-long-nickname');
SELECT 'strict.long', 'continued';

-- 二、ON DUPLICATE KEY UPDATE 的影响行数：新插入、改了值、值没变
INSERT INTO users (email, phone, nickname) VALUES ('f@example.com', '600', 'frank') AS new ON DUPLICATE KEY UPDATE points = users.points + 1;
SELECT 'odku.insert', ROW_COUNT();
INSERT INTO users (email, phone, nickname) VALUES ('f@example.com', '600', 'frank') AS new ON DUPLICATE KEY UPDATE points = users.points + 1;
SELECT 'odku.changed', ROW_COUNT();
INSERT INTO users (email, phone, nickname) VALUES ('f@example.com', '600', 'frank') AS new ON DUPLICATE KEY UPDATE nickname = new.nickname;
SELECT 'odku.same', ROW_COUNT();

-- 三、自增值：ON DUPLICATE KEY UPDATE 走了更新也会消耗自增值
INSERT INTO users (email, phone, nickname) VALUES ('f@example.com', '600', 'frank') AS new ON DUPLICATE KEY UPDATE points = users.points + 1;
INSERT INTO users (email, phone, nickname) VALUES ('f@example.com', '600', 'frank') AS new ON DUPLICATE KEY UPDATE points = users.points + 1;
INSERT INTO users (email, phone, nickname) VALUES ('f@example.com', '600', 'frank') AS new ON DUPLICATE KEY UPDATE points = users.points + 1;
INSERT INTO users (email, phone, nickname) VALUES ('g@example.com', '700', 'grace');
SELECT 'autoinc.next-id', id FROM users WHERE email = 'g@example.com';
SELECT 'autoinc.max-before', MAX(id) FROM users WHERE email <> 'g@example.com';

-- 四、两个唯一键分别与两行冲突：email 是 alice 的，phone 是 bob 的
INSERT INTO users (email, phone, nickname) VALUES ('a@example.com', '200', 'mix') AS new ON DUPLICATE KEY UPDATE nickname = new.nickname;
SELECT 'multi.rows', ROW_COUNT();
SELECT 'multi.result', GROUP_CONCAT(CONCAT(id, ':', nickname) ORDER BY id) FROM users WHERE id IN (1, 2);

-- 五、VALUES() 已弃用
INSERT INTO users (email, phone, nickname) VALUES ('g@example.com', '700', 'grace2') ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);
GET DIAGNOSTICS @n = NUMBER; GET DIAGNOSTICS CONDITION 1 @c = MYSQL_ERRNO;
SELECT 'values.warning', @c;

-- 六、REPLACE：先删后插；一行冲突、两行冲突；触发器与外键级联
SELECT 'replace.before', CONCAT((SELECT COUNT(*) FROM order_items), '|', (SELECT COUNT(*) FROM audit));
REPLACE INTO users (email, phone, nickname) VALUES ('b@example.com', '200', 'bob2');
SELECT 'replace.one.rows', ROW_COUNT();
SELECT 'replace.one.id', id FROM users WHERE email = 'b@example.com';
SELECT 'replace.one.after', CONCAT((SELECT COUNT(*) FROM order_items), '|', (SELECT GROUP_CONCAT(CONCAT(event, ':', user_id)) FROM audit));
REPLACE INTO users (email, phone, nickname) VALUES ('a@example.com', '700', 'merged');
SELECT 'replace.two.rows', ROW_COUNT();
SELECT 'replace.two.left', GROUP_CONCAT(CONCAT(email, '/', phone, '/', nickname) ORDER BY id) FROM users WHERE email IN ('a@example.com', 'g@example.com') OR phone = '700';
SELECT 'replace.two.after', CONCAT((SELECT COUNT(*) FROM order_items), '|', (SELECT COUNT(*) FROM audit));
