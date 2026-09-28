-- 容量拐点：10 万行的账户表，主键点查与主键更新
CREATE TABLE account (
    id      BIGINT PRIMARY KEY,
    balance BIGINT      NOT NULL,
    name    VARCHAR(32) NOT NULL
);
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO account (id, balance, name)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100000)
SELECT n, 1000, CONCAT('user-', n) FROM seq;
