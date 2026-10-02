-- 合成数据。第 1 批文件 7 行：2 行合格（A001、A007），5 行各有一种问题。
DROP TABLE IF EXISTS t_direct, raw_orders, rejects, orders_target;

-- 一、直接写入带类型的目标表
CREATE TABLE t_direct (
  order_no VARCHAR(20) PRIMARY KEY,
  amount   DECIMAL(10,2) NOT NULL,
  paid_on  DATE NOT NULL,
  status   ENUM('PAID','REFUNDED') NOT NULL
);
INSERT IGNORE INTO t_direct VALUES
  ('A001','100.00','2026-09-01','PAID'),
  ('A002','12.5abc','2026-09-02','PAID'),
  ('A003','50','2026-02-30','PAID'),
  ('A004','30','2026-09-03','UNKNOWN'),
  ('A001','100.00','2026-09-01','PAID'),
  ('  A005 ','-5','2026-09-04','PAID'),
  ('A007','70','2026-09-05','PAID');
SELECT 'direct.ignore.rows', COUNT(*) FROM t_direct;
SELECT 'direct.ignore.a002', CONCAT('amount=', amount) FROM t_direct WHERE order_no = 'A002';
SELECT 'direct.ignore.a003', CONCAT('paid_on=', paid_on) FROM t_direct WHERE order_no = 'A003';
SELECT 'direct.ignore.a004', CONCAT('status=[', status, ']') FROM t_direct WHERE order_no = 'A004';
SELECT 'direct.ignore.a005', CONCAT('order_no=[', order_no, '] amount=', amount) FROM t_direct WHERE order_no LIKE '%A005%';

-- 二、暂存表：原样保存每一行，全部是字符串
CREATE TABLE raw_orders (
  batch_id   INT NOT NULL,
  line_no    INT NOT NULL,
  order_no   VARCHAR(100),
  amount     VARCHAR(100),
  paid_on    VARCHAR(100),
  status     VARCHAR(100),
  source_ver VARCHAR(100),
  PRIMARY KEY (batch_id, line_no)
);
CREATE TABLE rejects (
  batch_id     INT NOT NULL,
  line_no      INT NOT NULL,
  rule_version VARCHAR(10) NOT NULL,
  reason       VARCHAR(40) NOT NULL,
  PRIMARY KEY (batch_id, line_no)
);
CREATE TABLE orders_target (
  order_no   VARCHAR(20) PRIMARY KEY,
  amount     DECIMAL(10,2) NOT NULL,
  paid_on    DATE NOT NULL,
  status     ENUM('PAID','REFUNDED') NOT NULL,
  source_ver INT NOT NULL,
  last_batch INT NOT NULL,
  deleted    TINYINT NOT NULL DEFAULT 0,
  CHECK (amount >= 0)
);
INSERT INTO raw_orders VALUES
  (1,1,'A001','100.00','2026-09-01','PAID','1'),
  (1,2,'A002','12.5abc','2026-09-02','PAID','1'),
  (1,3,'A003','50','2026-02-30','PAID','1'),
  (1,4,'A004','30','2026-09-03','UNKNOWN','1'),
  (1,5,'A001','100.00','2026-09-01','PAID','1'),
  (1,6,'  A005 ','-5','2026-09-04','PAID','1'),
  (1,7,'A007','70','2026-09-05','PAID','1');

DROP PROCEDURE IF EXISTS load_batch;
DELIMITER //
-- 按规则版本 r1 给一批数据分类：不合格的进拒绝表并写明原因，合格的按来源版本条件写入目标表
CREATE PROCEDURE load_batch(IN b INT)
BEGIN
  INSERT IGNORE INTO rejects
  SELECT batch_id, line_no, 'r1', reason FROM (
    SELECT batch_id, line_no, CASE
             WHEN order_no NOT REGEXP '^[A-Z][0-9]{3}$' THEN '订单号格式不符'
             WHEN amount NOT REGEXP '^-?[0-9]+(\\.[0-9]{1,2})?$' THEN '金额不是数字'
             WHEN CAST(amount AS DECIMAL(10,2)) < 0 THEN '金额为负'
             WHEN paid_on NOT REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' OR DATE(paid_on) IS NULL THEN '日期不存在'
             WHEN status NOT IN ('PAID','REFUNDED') THEN '状态不在允许范围'
             WHEN ROW_NUMBER() OVER (PARTITION BY batch_id, order_no ORDER BY line_no) > 1 THEN '文件内订单号重复'
           END AS reason
    FROM raw_orders WHERE batch_id = b) c
  WHERE reason IS NOT NULL;

  INSERT INTO orders_target (order_no, amount, paid_on, status, source_ver, last_batch)
  SELECT r.order_no, r.amount, r.paid_on, r.status, r.source_ver, r.batch_id
  FROM raw_orders r
  WHERE r.batch_id = b AND NOT EXISTS (SELECT 1 FROM rejects j WHERE j.batch_id = r.batch_id AND j.line_no = r.line_no)
  ON DUPLICATE KEY UPDATE
    amount     = IF(r.source_ver > orders_target.source_ver, r.amount, orders_target.amount),
    paid_on    = IF(r.source_ver > orders_target.source_ver, r.paid_on, orders_target.paid_on),
    status     = IF(r.source_ver > orders_target.source_ver, r.status, orders_target.status),
    last_batch = GREATEST(orders_target.last_batch, r.batch_id),
    deleted    = 0,
    source_ver = GREATEST(orders_target.source_ver, r.source_ver);
END//
DELIMITER ;

CALL load_batch(1);
SELECT 'staging.accepted', GROUP_CONCAT(CONCAT(order_no, '=', amount) ORDER BY order_no SEPARATOR ' ') FROM orders_target;
SELECT 'staging.rejects', GROUP_CONCAT(CONCAT('第', line_no, '行:', reason) ORDER BY line_no SEPARATOR '；') FROM rejects WHERE batch_id = 1;
SELECT 'staging.reconcile', CONCAT('原始 ', (SELECT COUNT(*) FROM raw_orders WHERE batch_id = 1), ' = 写入 ', (SELECT COUNT(*) FROM orders_target WHERE last_batch = 1), ' + 拒绝 ', (SELECT COUNT(*) FROM rejects WHERE batch_id = 1));
SELECT 'staging.raw_kept', CONCAT('第 2 行的原始金额仍是 [', amount, ']，第 6 行的原始订单号仍是 [', (SELECT order_no FROM raw_orders WHERE batch_id = 1 AND line_no = 6), ']') FROM raw_orders WHERE batch_id = 1 AND line_no = 2;

-- 三、重跑同一批：结果不变
CALL load_batch(1);
SELECT 'rerun', CONCAT('目标表 ', (SELECT COUNT(*) FROM orders_target), ' 行，拒绝表 ', (SELECT COUNT(*) FROM rejects), ' 行');

-- 四、第 2 批是来源的全量快照：A001 改了金额（来源版本 2），新增 A006，A007 在来源已经删除
INSERT INTO raw_orders VALUES
  (2,1,'A001','120.00','2026-09-01','PAID','2'),
  (2,2,'A006','60','2026-09-06','PAID','1');
CALL load_batch(2);
SELECT 'snapshot.upsert_only', GROUP_CONCAT(CONCAT(order_no, '=', amount) ORDER BY order_no SEPARATOR ' ') FROM orders_target WHERE deleted = 0;
UPDATE orders_target SET deleted = 1 WHERE last_batch < 2;        -- 全量快照里没出现的，标记为来源已删除
SELECT 'snapshot.mark_missing', CONCAT('未删除：', (SELECT GROUP_CONCAT(order_no ORDER BY order_no SEPARATOR ' ') FROM orders_target WHERE deleted = 0), '；标记删除：', (SELECT GROUP_CONCAT(order_no ORDER BY order_no SEPARATOR ' ') FROM orders_target WHERE deleted = 1));

-- 五、迟到的旧数据：第 3 批带着 A001 的来源版本 1（金额 100）
CREATE TABLE t_plain LIKE orders_target;
INSERT INTO t_plain SELECT * FROM orders_target;
INSERT INTO t_plain (order_no, amount, paid_on, status, source_ver, last_batch) VALUES ('A001', 100.00, '2026-09-01', 'PAID', 1, 3) AS n
  ON DUPLICATE KEY UPDATE amount = n.amount, source_ver = n.source_ver, last_batch = n.last_batch;
SELECT 'late.plain_upsert', CONCAT('amount=', amount, ' source_ver=', source_ver) FROM t_plain WHERE order_no = 'A001';
INSERT INTO raw_orders VALUES (3,1,'A001','100.00','2026-09-01','PAID','1');
CALL load_batch(3);
SELECT 'late.versioned_upsert', CONCAT('amount=', amount, ' source_ver=', source_ver) FROM orders_target WHERE order_no = 'A001';
DROP TABLE t_plain;
