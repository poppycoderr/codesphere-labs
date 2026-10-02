-- 合成数据，全部小到可以手算。时间字面量在会话时区 +00:00 下写入。
SET time_zone = '+00:00';
DROP TABLE IF EXISTS t_orders, daily, logins, scores, sales, city_sales, readings;

-- 1. 时间范围：9 月有 5 笔订单（合计 150），10 月 1 日零点整有 1 笔
CREATE TABLE t_orders (
  id         INT PRIMARY KEY,
  created_at DATETIME(3)  NOT NULL,
  created_ts TIMESTAMP(3) NOT NULL,
  amount     INT NOT NULL,
  KEY idx_created_at (created_at)
);
INSERT INTO t_orders VALUES
  (1,'2026-09-01 00:00:00.000','2026-09-01 00:00:00.000',10),
  (2,'2026-09-15 12:00:00.000','2026-09-15 12:00:00.000',20),
  (3,'2026-09-30 00:00:00.000','2026-09-30 00:00:00.000',30),
  (4,'2026-09-30 10:00:00.000','2026-09-30 10:00:00.000',40),
  (5,'2026-09-30 23:59:59.500','2026-09-30 23:59:59.500',50),
  (6,'2026-10-01 00:00:00.000','2026-10-01 00:00:00.000',60);

-- 2. 每日销售额：9 月 3 日没有记录
CREATE TABLE daily (day DATE PRIMARY KEY, amount INT NOT NULL);
INSERT INTO daily VALUES ('2026-09-01',100),('2026-09-02',120),('2026-09-04',90);

-- 3. 登录日期：u1 连续 3 天；u2 登录 3 天但不连续；u3 登录 5 天，其中 4—6 日连续
CREATE TABLE logins (user_id VARCHAR(4) NOT NULL, day DATE NOT NULL, PRIMARY KEY (user_id, day));
INSERT INTO logins VALUES
  ('u1','2026-09-01'),('u1','2026-09-02'),('u1','2026-09-03'),
  ('u2','2026-09-01'),('u2','2026-09-03'),('u2','2026-09-05'),
  ('u3','2026-09-01'),('u3','2026-09-02'),('u3','2026-09-04'),('u3','2026-09-05'),('u3','2026-09-06');

-- 4. 排名：两组并列
CREATE TABLE scores (id INT PRIMARY KEY, name VARCHAR(4) NOT NULL, score INT NOT NULL);
INSERT INTO scores VALUES (1,'a',90),(2,'b',90),(3,'c',80),(4,'d',80),(5,'e',70);

-- 5. 窗口帧：第 2 天有两行
CREATE TABLE sales (day INT NOT NULL, shop VARCHAR(4) NOT NULL, amount INT NOT NULL, PRIMARY KEY (day, shop));
INSERT INTO sales VALUES (1,'A',100),(2,'A',50),(2,'B',70),(3,'A',10);

-- 6. ROLLUP：有一行的城市本身就是 NULL
CREATE TABLE city_sales (id INT PRIMARY KEY, city VARCHAR(4) NULL, amount INT NOT NULL);
INSERT INTO city_sales VALUES (1,'X',100),(2,'Y',50),(3,NULL,30);

-- 7. 每个设备的最新读数：d1 在最新时刻有两行
CREATE TABLE readings (id INT PRIMARY KEY, device VARCHAR(4) NOT NULL, ts INT NOT NULL, v INT NOT NULL);
INSERT INTO readings VALUES (1,'d1',1,10),(2,'d1',2,20),(3,'d1',2,21),(4,'d2',1,5);
