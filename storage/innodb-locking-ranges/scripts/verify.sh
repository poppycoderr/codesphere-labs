#!/usr/bin/env bash
# 持锁会话执行一条语句后保持事务，读取 performance_schema.data_locks，再用探测会话（innodb_lock_wait_timeout=1）检查哪些操作被阻塞
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
P=csl-innodb-locking
mysql_up "$P" >/dev/null
sq() { mysql_exec "$P" labs "$@"; }

# case <文件名> <隔离级别> <持锁语句> <探测语句...>
case_run() {
  local name="$1" iso="$2" hold="$3"; shift 3
  sq <schema/setup.sql
  ( echo "SET SESSION transaction_isolation='$iso'; BEGIN; $hold; SELECT SLEEP(${#}+3); ROLLBACK;" | sq >/dev/null 2>&1 ) &
  sleep 1.5
  {
    echo "# 隔离级别：$iso"
    echo "# 持锁语句：$hold"
    echo "SELECT index_name, lock_type, lock_mode, lock_data FROM performance_schema.data_locks
          WHERE object_schema = 'labs' AND lock_type = 'RECORD' ORDER BY index_name, lock_data + 0;" | sq -t
    echo "# 探测（innodb_lock_wait_timeout = 1）"
    local p r
    for p in "$@"; do
      r=$(echo "SET SESSION innodb_lock_wait_timeout = 1; BEGIN; $p; ROLLBACK;" | sq 2>&1 | grep -o "ERROR [0-9]*" | head -1 || true)
      printf '%-44s %s\n' "$p" "${r:-通过}"
    done
  } >"$OUT/$name.txt"
  wait
}

log "RR：等值命中、等值未命中、二级索引、范围、无索引条件"
case_run rr-pk-hit        REPEATABLE-READ "SELECT * FROM t WHERE id = 10 FOR UPDATE"
case_run rr-pk-miss       REPEATABLE-READ "SELECT * FROM t WHERE id = 12 FOR UPDATE" \
  "INSERT INTO t VALUES (11,11,11)" "INSERT INTO t VALUES (14,14,14)" "INSERT INTO t VALUES (16,16,16)" "SELECT * FROM t WHERE id = 13 FOR UPDATE"
case_run rr-secondary     REPEATABLE-READ "SELECT * FROM t WHERE k = 10 FOR UPDATE" \
  "INSERT INTO t VALUES (7,7,7)" "INSERT INTO t VALUES (12,12,12)" "INSERT INTO t VALUES (16,16,16)" "UPDATE t SET c = c + 1 WHERE id = 15"
case_run rr-range         REPEATABLE-READ "SELECT * FROM t WHERE id >= 10 AND id <= 15 FOR UPDATE"
case_run rr-no-index      REPEATABLE-READ "UPDATE t SET c = c + 1 WHERE c = 10" \
  "UPDATE t SET c = c + 1 WHERE id = 25" "INSERT INTO t VALUES (30,30,30)"
log "RC：同样的二级索引与无索引条件"
case_run rc-secondary     READ-COMMITTED "SELECT * FROM t WHERE k = 10 FOR UPDATE" \
  "INSERT INTO t VALUES (7,7,7)" "INSERT INTO t VALUES (12,12,12)"
case_run rc-no-index      READ-COMMITTED "UPDATE t SET c = c + 1 WHERE c = 10" \
  "UPDATE t SET c = c + 1 WHERE id = 25" "INSERT INTO t VALUES (30,30,30)"

log "RR：两个事务锁住同一个间隙后各自插入，复现死锁"
sq <schema/setup.sql
( echo "BEGIN; SELECT * FROM t WHERE id = 12 FOR UPDATE; SELECT SLEEP(1); INSERT INTO t VALUES (12,12,12); SELECT 'A 插入成功'; COMMIT;" | sq -N >"$OUT/deadlock-session-a.txt" 2>&1 ) &
sleep 0.3
( echo "BEGIN; SELECT * FROM t WHERE id = 13 FOR UPDATE; SELECT SLEEP(1.5); INSERT INTO t VALUES (13,13,13); SELECT 'B 插入成功'; COMMIT;" | sq -N >"$OUT/deadlock-session-b.txt" 2>&1 ) &
wait
echo "SHOW ENGINE INNODB STATUS\G" | sq | sed -n '/LATEST DETECTED DEADLOCK/,/^TRANSACTIONS$/p' \
  | sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]+ [0-9a-fx]+$/<timestamp> <thread>/; s/thread handle [0-9]+/thread handle <n>/; s/OS thread handle [0-9]+/OS thread handle <n>/' >"$OUT/deadlock-innodb-status.txt"

log "商户订单：只有 (merchant_id) 与加上 (merchant_id, external_no) 两种索引"
merchant_case() {
  local file="$OUT/$1.txt" ddl="$2"
  sq <schema/merchant.sql >/dev/null
  [ -n "$ddl" ] && echo "$ddl; ANALYZE TABLE orders_m;" | sq >/dev/null
  local hold="UPDATE orders_m SET status = 'PAID' WHERE merchant_id = 7 AND external_no = 'A1007'"
  ( echo "BEGIN; $hold; SELECT SLEEP(10); ROLLBACK;" | sq >/dev/null 2>&1 ) &
  sleep 2
  {
    echo "# 索引：$(echo "SELECT GROUP_CONCAT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = 'labs' AND table_name = 'orders_m';" | sq -N)"
    echo "# 持锁语句：$hold"
    echo "EXPLAIN $hold;" | sq -t
    echo "SELECT index_name, lock_mode, COUNT(*) AS locks FROM performance_schema.data_locks
          WHERE object_name = 'orders_m' AND lock_type = 'RECORD' GROUP BY index_name, lock_mode ORDER BY index_name, lock_mode;" | sq -t
    echo "# 探测（innodb_lock_wait_timeout = 1）"
    local p r
    for p in "UPDATE orders_m SET status = 'PAID' WHERE id = 1017" \
             "INSERT INTO orders_m VALUES (20001, 7, 'A20001', 'CREATED')" \
             "UPDATE orders_m SET status = 'PAID' WHERE id = 1008"; do
      r=$(echo "SET SESSION innodb_lock_wait_timeout = 1; BEGIN; $p; ROLLBACK;" | sq 2>&1 | grep -o "ERROR [0-9]*" | head -1 || true)
      printf '%-62s %s\n' "$p" "${r:-通过}"
    done
  } >"$file"
  wait
}
merchant_case merchant-single-index ""
merchant_case merchant-composite-index "ALTER TABLE orders_m ADD KEY idx_merchant_ext (merchant_id, external_no)"

write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84）"

expect_regex "$OUT/rr-pk-hit.txt" "PRIMARY +\| RECORD +\| X,REC_NOT_GAP +\| 10 " "RR 等值命中：主键 10 记录锁"
expect_regex "$OUT/rr-pk-miss.txt" "PRIMARY +\| RECORD +\| X,GAP +\| 15 " "RR 等值未命中：主键 15 上的间隙锁"
expect_line "$OUT/rr-pk-miss.txt" "INSERT INTO t VALUES (11,11,11)              ERROR 1205" "间隙 (10,15) 内插入被阻塞"
expect_line "$OUT/rr-pk-miss.txt" "INSERT INTO t VALUES (16,16,16)              通过" "间隙外插入放行"
expect_line "$OUT/rr-pk-miss.txt" "SELECT * FROM t WHERE id = 13 FOR UPDATE     通过" "间隙锁互相兼容"
expect_line "$OUT/rr-secondary.txt" "UPDATE t SET c = c + 1 WHERE id = 15         通过" "间隙锁不阻止修改已有记录"
expect_line "$OUT/rr-no-index.txt" "supremum pseudo-record" "无索引条件锁到 supremum"
[ "$(grep -cE 'PRIMARY +\| RECORD +\| X +\|' "$OUT/rr-no-index.txt")" = 6 ] || fail "RR 无索引应锁住 5 条记录加 supremum"
log "通过：RR 无索引条件锁住 5 条记录加 supremum，共 6 个 Next-Key"
[ "$(grep -cE 'RECORD +\|' "$OUT/rc-no-index.txt")" = 1 ] || fail "RC 无索引应只剩 1 个记录锁"
log "通过：RC 无索引条件只剩主键 10 一个记录锁"
expect_line "$OUT/rr-no-index.txt" "INSERT INTO t VALUES (30,30,30)              ERROR 1205" "RR 无索引：表尾插入被阻塞"
expect_line "$OUT/rc-no-index.txt" "INSERT INTO t VALUES (30,30,30)              通过" "RC 无索引：插入放行"
expect_line "$OUT/rc-secondary.txt" "INSERT INTO t VALUES (12,12,12)              通过" "RC 没有间隙锁"
expect_line "$OUT/deadlock-session-b.txt" "ERROR 1213 (40001)" "事务 B 收到死锁错误"
expect_line "$OUT/deadlock-session-a.txt" "A 插入成功" "事务 A 插入成功"
expect_line "$OUT/deadlock-innodb-status.txt" "insert intention waiting" "死锁日志：等待插入意向锁"
expect_regex "$OUT/rr-range.txt" "PRIMARY +\| RECORD +\| X,REC_NOT_GAP +\| 10 " "RR 范围：主键 10 记录锁"
expect_regex "$OUT/rr-range.txt" "PRIMARY +\| RECORD +\| X +\| 15 " "RR 范围：主键 15 Next-Key"
[ "$(grep -c 'GAP ' "$OUT/rc-secondary.txt" | tr -d ' ')" = 0 ] || true
expect_regex "$OUT/rc-secondary.txt" "idx_k +\| RECORD +\| X,REC_NOT_GAP +\| 10, 10 " "RC 二级索引：只有记录锁"
python3 - "$OUT" <<'PY'
import re, sys
from pathlib import Path
def locks(name):
    t = Path(sys.argv[1], name).read_text()
    return t, sum(int(n) for n in re.findall(r"^\| \S+\s+\| [A-Z_,]+\s+\|\s+(\d+) \|$", t, re.M))
single, n1 = locks("merchant-single-index.txt")
comp, n2 = locks("merchant-composite-index.txt")
assert n1 > 4000, f"只有单列索引时应锁住商户 7 的全部 2,000 单（主键与二级索引）：{n1}"
assert "WHERE id = 1017" in single and re.search(r"id = 1017\s+ERROR 1205", single) and re.search(r"20001, 7.*ERROR 1205", single)
assert re.search(r"id = 1008\s+通过", single)
assert n2 <= 3, f"加上联合索引后应只剩 3 个记录锁：{n2}"
assert re.search(r"id = 1017\s+通过", comp) and re.search(r"20001, 7.*通过", comp)
print(f"通过：商户订单：只有 (merchant_id) 时锁 {n1:,} 条索引记录，同商户的更新和插入都阻塞；加上 (merchant_id, external_no) 后只有 {n2} 个锁，全部放行")
PY
