-- 以 utf8mb4 客户端执行；每一步输出「键<TAB>值」
DROP TABLE IF EXISTS t3, t4, users_ci, users_bin, pad, money;
CREATE TABLE t3 (s VARCHAR(20)) CHARACTER SET utf8mb3;
CREATE TABLE t4 (s VARCHAR(20)) CHARACTER SET utf8mb4;

-- 一、utf8mb3 存不下四字节字符
INSERT INTO t4 VALUES ('好的👍');
SELECT 'mb4.len', CONCAT(CHAR_LENGTH(s), '|', LENGTH(s), '|', HEX(s)) FROM t4;

-- 二、排序规则：大小写、重音、尾随空格
SELECT 'coll.default', CONCAT(@@collation_server, '|', (SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = 'labs'));
SELECT 'coll.ai_ci', CONCAT('jose' = 'José' COLLATE utf8mb4_0900_ai_ci, '|', 'jose' = 'JOSE' COLLATE utf8mb4_0900_ai_ci, '|', 'jose' = 'José' COLLATE utf8mb4_0900_as_cs, '|', 'jose' = 'José' COLLATE utf8mb4_bin);
SELECT 'coll.pad', CONCAT('a' = 'a ' COLLATE utf8mb4_0900_ai_ci, '|', 'a' = 'a ' COLLATE utf8mb4_general_ci, '|',
  (SELECT PAD_ATTRIBUTE FROM information_schema.COLLATIONS WHERE COLLATION_NAME = 'utf8mb4_0900_ai_ci'), '|',
  (SELECT PAD_ATTRIBUTE FROM information_schema.COLLATIONS WHERE COLLATION_NAME = 'utf8mb4_general_ci'));
CREATE TABLE users_ci (name VARCHAR(20) PRIMARY KEY) COLLATE utf8mb4_0900_ai_ci;
CREATE TABLE users_bin (name VARCHAR(20) PRIMARY KEY) COLLATE utf8mb4_bin;
INSERT INTO users_ci VALUES ('José');
INSERT INTO users_bin VALUES ('José'), ('jose'), ('JOSE');
SELECT 'coll.bin.count', COUNT(*) FROM users_bin;
SELECT 'coll.lookup', CONCAT((SELECT COUNT(*) FROM users_ci WHERE name = 'jose'), '|', (SELECT COUNT(*) FROM users_bin WHERE name = 'jose'));

-- 三、DECIMAL
CREATE TABLE money (amount DECIMAL(10,2));
INSERT INTO money VALUES (1.005);
GET DIAGNOSTICS CONDITION 1 @c = MYSQL_ERRNO, @lvl = RETURNED_SQLSTATE;
SHOW WARNINGS;
SELECT 'dec.round', CONCAT((SELECT amount FROM money LIMIT 1), '|', @c);
SELECT 'dec.div', CONCAT(CAST(1 AS DECIMAL(10,2)) / 3, '|', @@div_precision_increment, '|', CAST(1 AS DECIMAL(10,2)) / 3 * 3);
SELECT 'dec.sum', CONCAT(CAST(0.1 AS DECIMAL(10,2)) + CAST(0.2 AS DECIMAL(10,2)) = 0.3, '|', CAST(0.1 AS DOUBLE) + CAST(0.2 AS DOUBLE) = CAST(0.3 AS DOUBLE));
