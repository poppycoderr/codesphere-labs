-- 100 万个确定性生成的 IPv4 地址，分别存为字符串与 VARBINARY(16)
SET SESSION cte_max_recursion_depth = 2000000;
DROP TABLE IF EXISTS access_log_str, access_log_bin;
CREATE TABLE access_log_str (id INT PRIMARY KEY, ip VARCHAR(45) NOT NULL, KEY idx_ip (ip));
CREATE TABLE access_log_bin (id INT PRIMARY KEY, ip VARBINARY(16) NOT NULL, KEY idx_ip (ip));
-- 10.0.0.0/8 内的地址：x = n × 7919 mod 2^24 是 n 的一个置换，拆成三个字节，100 万个地址互不相同、均匀分布在各个 /16 网段
INSERT INTO access_log_str
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 1000000)
SELECT n, CONCAT('10.', x >> 16, '.', (x >> 8) & 255, '.', x & 255)
FROM (SELECT n, (n * 7919) % 16777216 AS x FROM s) t;
INSERT INTO access_log_bin SELECT id, INET6_ATON(ip) FROM access_log_str;
-- 地址随机插入会让索引页频繁分裂；重建后两个索引都按排序批量构建，占用空间才可比较
ALTER TABLE access_log_str FORCE;
ALTER TABLE access_log_bin FORCE;
ANALYZE TABLE access_log_str, access_log_bin;
