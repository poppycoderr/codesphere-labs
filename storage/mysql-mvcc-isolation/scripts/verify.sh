#!/usr/bin/env bash
# 两个会话按固定延时交错执行，记录每一步的返回值；长事务场景读取 History list length
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
P=csl-mvcc-isolation
mysql_up "$P" >/dev/null
sq() { mysql_exec "$P" labs "$@"; }
reset() { sq <schema/setup.sql; }

# 场景一：RR 与 RC 的快照建立时机（T1—T5）
timeline() {
  local iso="$1" file="$OUT/timeline-$2.txt"
  reset
  ( echo "SET SESSION transaction_isolation = '$iso'; BEGIN;
          SELECT 'T2 快照读' AS step, balance FROM account WHERE id = 1;
          DO SLEEP(4);
          SELECT 'T4 快照读', balance FROM account WHERE id = 1;
          SELECT 'T5 当前读 FOR UPDATE', balance FROM account WHERE id = 1 FOR UPDATE;
          COMMIT;" | sq -N >"$file.a" ) &
  sleep 2
  echo "UPDATE account SET balance = 80 WHERE id = 1;" | sq
  wait
  { echo "# 隔离级别：$iso；T3 时会话 B 执行 UPDATE account SET balance = 80 并自动提交"; cat "$file.a"; } >"$file"; rm "$file.a"
}
log "场景一：RR 与 RC 下同一事务两次快照读"
timeline REPEATABLE-READ rr
timeline READ-COMMITTED rc

# 场景二：RR 快照在第一次一致性读时建立，而不是 BEGIN 时
snapshot_start() {
  local begin="$1" file="$OUT/$2.txt"
  reset
  ( echo "SET SESSION transaction_isolation = 'REPEATABLE-READ'; $begin;
          DO SLEEP(4);
          SELECT '第一次快照读', balance FROM account WHERE id = 1;
          COMMIT;" | sq -N >"$file.a" ) &
  sleep 2
  echo "UPDATE account SET balance = 80 WHERE id = 1;" | sq
  wait
  { echo "# RR；事务开始语句：$begin；2 秒后会话 B 把余额改成 80 并提交，4 秒后会话 A 第一次读"; cat "$file.a"; } >"$file"; rm "$file.a"
}
log "场景二：BEGIN 与 START TRANSACTION WITH CONSISTENT SNAPSHOT"
snapshot_start "BEGIN" snapshot-begin
snapshot_start "START TRANSACTION WITH CONSISTENT SNAPSHOT" snapshot-consistent

# 场景三：快照读与当前读混用（RR）
log "场景三：查不到却改得到"
reset
( echo "SET SESSION transaction_isolation = 'REPEATABLE-READ'; BEGIN;
        SELECT '快照读 NEW', COUNT(*) FROM coupon WHERE status = 'NEW';
        DO SLEEP(4);
        SELECT '再次快照读 NEW', COUNT(*) FROM coupon WHERE status = 'NEW';
        UPDATE coupon SET status = 'EXPIRED' WHERE status = 'NEW';
        SELECT 'UPDATE 影响行数', ROW_COUNT();
        SELECT '快照读 EXPIRED', COUNT(*) FROM coupon WHERE status = 'EXPIRED';
        ROLLBACK;" | sq -N >"$OUT/snapshot-vs-current.a" ) &
sleep 2
echo "INSERT INTO coupon (status) VALUES ('NEW'),('NEW'),('NEW'),('NEW'),('NEW'),('NEW'),('NEW'),('NEW'),('NEW'),('NEW');" | sq
wait
{ echo "# RR；2 秒后会话 B 插入 10 条 NEW 并提交"; cat "$OUT/snapshot-vs-current.a"; } >"$OUT/snapshot-vs-current.txt"; rm "$OUT/snapshot-vs-current.a"

# 场景四：幻读的三种读法
log "场景四：快照读、加锁读、RC 加锁读下的新插入"
phantom() {
  local iso="$1" read="$2" file="$OUT/phantom-$3.txt"
  reset
  ( echo "SET SESSION transaction_isolation = '$iso'; BEGIN;
          SELECT '第一次读', COUNT(*) FROM coupon WHERE id >= 2 $read;
          DO SLEEP(4);
          SELECT '第二次读', COUNT(*) FROM coupon WHERE id >= 2 $read;
          COMMIT;" | sq -N >"$file.a" ) &
  sleep 2
  local r
  r=$(echo "SET SESSION innodb_lock_wait_timeout = 1; INSERT INTO coupon (id, status) VALUES (10, 'NEW');" | sq 2>&1 | grep -o "ERROR [0-9]*" || true)
  wait
  { echo "# 隔离级别：$iso；读法：id >= 2 ${read:-（快照读）}；2 秒后会话 B 插入 id = 10（锁等待超时 1 秒）"
    cat "$file.a"; echo "会话 B 插入：${r:-成功}"; } >"$file"; rm "$file.a"
}
phantom REPEATABLE-READ "" rr-snapshot
phantom REPEATABLE-READ "FOR UPDATE" rr-locking
phantom READ-COMMITTED "FOR UPDATE" rc-locking

# 场景五：无索引 UPDATE，RR 锁住扫描到的所有行，RC 做半一致性读
log "场景五：半一致性读"
semi() {
  local iso="$1" file="$OUT/semi-consistent-$2.txt"
  reset
  ( echo "SET SESSION transaction_isolation = '$iso'; BEGIN; UPDATE t_noindex SET b = 5 WHERE b = 3; DO SLEEP(4); ROLLBACK;" | sq ) &
  sleep 2
  local r
  r=$(echo "SET SESSION transaction_isolation = '$iso'; SET SESSION innodb_lock_wait_timeout = 1; BEGIN; UPDATE t_noindex SET b = 4 WHERE b = 2; SELECT ROW_COUNT(); ROLLBACK;" | sq -N 2>&1 | grep -o "ERROR [0-9]*\|^[0-9]*$" | head -1 || true)
  wait
  { echo "# 隔离级别：$iso；会话 A：UPDATE t_noindex SET b = 5 WHERE b = 3（未提交）"
    echo "# 会话 B：UPDATE t_noindex SET b = 4 WHERE b = 2（锁等待超时 1 秒）"
    echo "会话 B 结果：$r"; } >"$file"
}
semi REPEATABLE-READ rr
semi READ-COMMITTED rc

# 场景六：长事务持有快照，History list length 持续上涨；提交后回落
# History list length 按事务计数（每个提交的更新事务留下一组 undo），所以用大量单行小事务制造历史版本
log "场景六：长事务与 purge"
reset
echo "SET SESSION cte_max_recursion_depth = 5000; INSERT INTO history_probe WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 2000) SELECT n, 0 FROM s;" | sq
sq <<'SQL'
DROP PROCEDURE IF EXISTS churn;
DELIMITER //
CREATE PROCEDURE churn() BEGIN
  DECLARE i INT DEFAULT 1;
  WHILE i <= 2000 DO UPDATE history_probe SET v = v + 1 WHERE id = i; SET i = i + 1; END WHILE;
END //
DELIMITER ;
SQL
hll() { echo "SELECT count FROM information_schema.innodb_metrics WHERE name = 'trx_rseg_history_len';" | sq -N; }
wait_purged() { local i; for i in $(seq 30); do [ "$(hll)" -lt "$1" ] && return; sleep 1; done; }
wait_purged 100
{
  echo "# 会话 A 以 WITH CONSISTENT SNAPSHOT 开启只读事务并保持；会话 B 每轮执行 2,000 个自动提交的单行 UPDATE"
  echo "阶段	history_list_length"
  echo "开始前	$(hll)"
  ( echo "START TRANSACTION WITH CONSISTENT SNAPSHOT; SELECT COUNT(*) FROM history_probe; DO SLEEP(30); COMMIT;" | sq >/dev/null ) &
  sleep 2
  for round in 1 2 3 4 5; do
    echo "CALL churn();" | sq
    echo "长事务持有中，第 $round 轮后	$(hll)"
  done
  wait
  start=$(date +%s); wait_purged 100
  echo "长事务提交后（再等待 $(( $(date +%s) - start )) 秒）	$(hll)"
} >"$OUT/long-transaction.txt"

write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84）"

expect_line "$OUT/timeline-rr.txt" "T2 快照读	100" "RR T2 读到 100"
expect_line "$OUT/timeline-rr.txt" "T4 快照读	100" "RR T4 仍读到 100"
expect_line "$OUT/timeline-rc.txt" "T4 快照读	80" "RC T4 读到 80"
expect_line "$OUT/timeline-rr.txt" "T5 当前读 FOR UPDATE	80" "RR 当前读读到 80"
expect_line "$OUT/timeline-rc.txt" "T5 当前读 FOR UPDATE	80" "RC 当前读读到 80"
expect_line "$OUT/snapshot-begin.txt" "第一次快照读	80" "BEGIN 后没有读过：第一次快照读看到 80"
expect_line "$OUT/snapshot-consistent.txt" "第一次快照读	100" "WITH CONSISTENT SNAPSHOT：看到 100"
expect_line "$OUT/snapshot-vs-current.txt" "再次快照读 NEW	0" "RR 快照读看不到新插入的 10 行"
expect_line "$OUT/snapshot-vs-current.txt" "UPDATE 影响行数	10" "UPDATE 当前读改到 10 行"
expect_line "$OUT/snapshot-vs-current.txt" "快照读 EXPIRED	10" "自己改过的行变得可见"
expect_line "$OUT/phantom-rr-snapshot.txt" "第二次读	2" "RR 快照读：看不到新行"
expect_line "$OUT/phantom-rr-snapshot.txt" "会话 B 插入：成功" "RR 快照读不阻止插入"
expect_line "$OUT/phantom-rr-locking.txt" "会话 B 插入：ERROR 1205" "RR 加锁读：插入被 Next-Key Lock 阻塞"
expect_line "$OUT/phantom-rr-locking.txt" "第二次读	2" "RR 加锁读：第二次仍是 2 行"
expect_line "$OUT/phantom-rc-locking.txt" "会话 B 插入：成功" "RC 加锁读：插入不被阻塞"
expect_line "$OUT/phantom-rc-locking.txt" "第二次读	3" "RC 加锁读：第二次读到新行（幻读）"
expect_line "$OUT/semi-consistent-rr.txt" "会话 B 结果：ERROR 1205" "RR：无索引 UPDATE 锁住扫描到的所有行"
expect_line "$OUT/semi-consistent-rc.txt" "会话 B 结果：3" "RC：半一致性读跳过不匹配的锁定行，更新 3 行"
python3 - "$OUT/long-transaction.txt" <<'PY'
import sys
rows = [l.split("\t") for l in open(sys.argv[1]).read().splitlines()[2:]]
v = [int(r[1]) for r in rows]
held, after = v[1:-1], v[-1]
assert all(b >= a for a, b in zip(held, held[1:])), f"持有期间应单调上涨：{held}"
assert held[-1] >= held[0] + 8000, f"持有期间至少增加 4,000：{held}"
assert after < held[-1] / 10, f"提交后应大幅回落：{after} vs {held[-1]}"
print(f"通过：长事务持有期间 History list length {held[0]} → {held[-1]}，提交后 {after}")
PY
