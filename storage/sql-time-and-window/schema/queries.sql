SET time_zone = '+00:00';

-- 1. 「9 月的订单」：正确答案 5 笔、合计 150
SELECT 'range.between_dates', CONCAT(COUNT(*), ' 笔 / ', SUM(amount)) FROM t_orders WHERE created_at BETWEEN '2026-09-01' AND '2026-09-30';
SELECT 'range.between_235959', CONCAT(COUNT(*), ' 笔 / ', SUM(amount)) FROM t_orders WHERE created_at BETWEEN '2026-09-01' AND '2026-09-30 23:59:59';
SELECT 'range.between_next_month', CONCAT(COUNT(*), ' 笔 / ', SUM(amount)) FROM t_orders WHERE created_at BETWEEN '2026-09-01' AND '2026-10-01';
SELECT 'range.half_open', CONCAT(COUNT(*), ' 笔 / ', SUM(amount)) FROM t_orders WHERE created_at >= '2026-09-01' AND created_at < '2026-10-01';
SELECT 'range.date_function', CONCAT(COUNT(*), ' 笔 / ', SUM(amount)) FROM t_orders WHERE DATE(created_at) BETWEEN '2026-09-01' AND '2026-09-30';

-- 2. 同一条半开区间查询，换会话时区
SELECT 'tz.utc.timestamp', CONCAT(COUNT(*), ' 笔 / ', SUM(amount)) FROM t_orders WHERE created_ts >= '2026-09-01' AND created_ts < '2026-10-01';
SET time_zone = '+08:00';
SELECT 'tz.plus8.timestamp', CONCAT(COUNT(*), ' 笔 / ', SUM(amount)) FROM t_orders WHERE created_ts >= '2026-09-01' AND created_ts < '2026-10-01';
SELECT 'tz.plus8.datetime', CONCAT(COUNT(*), ' 笔 / ', SUM(amount)) FROM t_orders WHERE created_at >= '2026-09-01' AND created_at < '2026-10-01';
SELECT 'tz.plus8.row5_display', CONCAT('TIMESTAMP 列显示 ', created_ts, '；DATETIME 列显示 ', created_at) FROM t_orders WHERE id = 5;
SET time_zone = '+00:00';

-- 3. LAG 取的是上一行，不是前一天
SELECT 'lag.previous_row', GROUP_CONCAT(CONCAT(DATE_FORMAT(day, '%m-%d'), ':', IFNULL(diff, 'NULL')) ORDER BY day SEPARATOR ' ') FROM (
  SELECT day, amount - LAG(amount) OVER (ORDER BY day) AS diff FROM daily) t;
SELECT 'lag.only_if_adjacent', GROUP_CONCAT(CONCAT(DATE_FORMAT(day, '%m-%d'), ':', IFNULL(diff, 'NULL')) ORDER BY day SEPARATOR ' ') FROM (
  SELECT day, CASE WHEN DATEDIFF(day, LAG(day) OVER (ORDER BY day)) = 1 THEN amount - LAG(amount) OVER (ORDER BY day) END AS diff FROM daily) t;
SELECT 'lag.calendar_filled', GROUP_CONCAT(CONCAT(DATE_FORMAT(day, '%m-%d'), ':', IFNULL(diff, 'NULL')) ORDER BY day SEPARATOR ' ') FROM (
  WITH RECURSIVE cal (day) AS (SELECT DATE '2026-09-01' UNION ALL SELECT day + INTERVAL 1 DAY FROM cal WHERE day < '2026-09-04')
  SELECT cal.day, COALESCE(d.amount, 0) - LAG(COALESCE(d.amount, 0)) OVER (ORDER BY cal.day) AS diff
  FROM cal LEFT JOIN daily d ON d.day = cal.day) t;

-- 4. 「登录满 3 天」与「连续登录 3 天」
SELECT 'streak.count_ge_3', GROUP_CONCAT(user_id ORDER BY user_id SEPARATOR ' ') FROM (
  SELECT user_id FROM logins GROUP BY user_id HAVING COUNT(*) >= 3) t;
SELECT 'streak.consecutive_ge_3', GROUP_CONCAT(DISTINCT user_id ORDER BY user_id SEPARATOR ' ') FROM (
  SELECT user_id, grp FROM (
    SELECT user_id, day - INTERVAL ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY day) DAY AS grp FROM logins) g
  GROUP BY user_id, grp HAVING COUNT(*) >= 3) t;

-- 5. 三种排名函数与「前 3 名」
SELECT 'rank.rank', GROUP_CONCAT(CONCAT(name, ':', r) ORDER BY id SEPARATOR ' ') FROM (SELECT id, name, RANK() OVER (ORDER BY score DESC) AS r FROM scores) t;
SELECT 'rank.dense_rank', GROUP_CONCAT(CONCAT(name, ':', r) ORDER BY id SEPARATOR ' ') FROM (SELECT id, name, DENSE_RANK() OVER (ORDER BY score DESC) AS r FROM scores) t;
SELECT 'rank.row_number', GROUP_CONCAT(CONCAT(name, ':', r) ORDER BY id SEPARATOR ' ') FROM (SELECT id, name, ROW_NUMBER() OVER (ORDER BY score DESC, id) AS r FROM scores) t;
SELECT 'rank.top3_rows', CONCAT('RANK<=3 得 ', (SELECT COUNT(*) FROM (SELECT RANK() OVER (ORDER BY score DESC) AS r FROM scores) t WHERE r <= 3),
  ' 行；DENSE_RANK<=3 得 ', (SELECT COUNT(*) FROM (SELECT DENSE_RANK() OVER (ORDER BY score DESC) AS r FROM scores) t WHERE r <= 3),
  ' 行；ROW_NUMBER<=3 得 ', (SELECT COUNT(*) FROM (SELECT ROW_NUMBER() OVER (ORDER BY score DESC, id) AS r FROM scores) t WHERE r <= 3), ' 行');

-- 6. 窗口帧：只写 ORDER BY 时默认是 RANGE，同一天的两行互为同级
SELECT 'frame.default_range', GROUP_CONCAT(s ORDER BY day, shop SEPARATOR ' ') FROM (SELECT day, shop, SUM(amount) OVER (ORDER BY day) AS s FROM sales) t;
SELECT 'frame.rows', GROUP_CONCAT(s ORDER BY day, shop SEPARATOR ' ') FROM (SELECT day, shop, SUM(amount) OVER (ORDER BY day, shop ROWS UNBOUNDED PRECEDING) AS s FROM sales) t;
SELECT 'frame.last_value_default', GROUP_CONCAT(s ORDER BY day, shop SEPARATOR ' ') FROM (SELECT day, shop, LAST_VALUE(amount) OVER (ORDER BY day, shop) AS s FROM sales) t;
SELECT 'frame.last_value_full', GROUP_CONCAT(s ORDER BY day, shop SEPARATOR ' ') FROM (
  SELECT day, shop, LAST_VALUE(amount) OVER (ORDER BY day, shop ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS s FROM sales) t;

-- 7. ROLLUP 的总计行与本来就是 NULL 的分组
SELECT 'rollup.rows', GROUP_CONCAT(CONCAT(IFNULL(city, 'NULL'), ':', total, ':g', g) ORDER BY g, city SEPARATOR ' ') FROM (
  SELECT city, SUM(amount) AS total, GROUPING(city) AS g FROM city_sales GROUP BY city WITH ROLLUP) t;
SELECT 'rollup.null_city_rows', COUNT(*) FROM (SELECT city, SUM(amount) AS total FROM city_sales GROUP BY city WITH ROLLUP) t WHERE city IS NULL;

-- 8. 每个设备的最新读数
SELECT 'latest.join_max', CONCAT(COUNT(*), ' 行：', GROUP_CONCAT(CONCAT(r.device, '=', r.v) ORDER BY r.id SEPARATOR ' ')) FROM readings r
  JOIN (SELECT device, MAX(ts) AS ts FROM readings GROUP BY device) m ON m.device = r.device AND m.ts = r.ts;
SELECT 'latest.row_number', CONCAT(COUNT(*), ' 行：', GROUP_CONCAT(CONCAT(device, '=', v) ORDER BY id SEPARATOR ' ')) FROM (
  SELECT id, device, v, ROW_NUMBER() OVER (PARTITION BY device ORDER BY ts DESC, id DESC) AS rn FROM readings) t WHERE rn = 1;
