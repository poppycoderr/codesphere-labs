#!/usr/bin/env bash
# count(*)、count(1)、count(主键)、count(列)：结果的差别（NULL）、执行计划选的索引、耗时；TABLE_ROWS 是估算
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-count-semantics），结束时删除；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-count-semantics
trap 'rc=$?; mysql_down "$P" >/dev/null 2>&1 || true; exit $rc' EXIT
mysql_up "$P" >/dev/null 2>&1
mysql_exec "$P" labs <schema/setup.sql >/dev/null
q() { mysql_exec "$P" labs -N -e "$1"; }
f="$OUT/output.tsv"; : >"$f"
for expr in 'COUNT(*)' 'COUNT(1)' 'COUNT(id)' 'COUNT(coupon_id)' 'COUNT(remark)'; do
  result=$(q "SELECT $expr FROM orders")
  key=$(q "EXPLAIN FORMAT=JSON SELECT $expr FROM orders" | grep -o '"key": "[^"]*"' | head -1 | sed 's/"key": //; s/"//g' || true)
  times=()
  for i in 1 2 3 4 5 6; do
    t=$(q "EXPLAIN ANALYZE SELECT $expr FROM orders" | grep -o 'actual time=[0-9.]*\.\.[0-9.]*' | head -1 | sed 's/.*\.\.//')
    [ "$i" -gt 1 ] && times+=("$t")
  done
  median=$(printf '%s\n' "${times[@]}" | sort -n | sed -n 3p)
  printf '%s\t%s\t%s\t%s\n' "$expr" "$result" "${key:-全表}" "$median" >>"$OUT/raw.tsv"
done
# TABLE_ROWS 是统计估算：删掉 10 万行后、ANALYZE 之前与之后
real=$(q "SELECT COUNT(*) FROM orders")
est=$(q "SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='labs' AND TABLE_NAME='orders'")
q "DELETE FROM orders WHERE id <= 100000"
real2=$(q "SELECT COUNT(*) FROM orders")
est2=$(q "SET SESSION information_schema_stats_expiry = 0; SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='labs' AND TABLE_NAME='orders'")
q "ANALYZE TABLE orders" >/dev/null
est3=$(q "SET SESSION information_schema_stats_expiry = 0; SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='labs' AND TABLE_NAME='orders'")
python3 - "$OUT/raw.tsv" "$real" "$est" "$real2" "$est2" "$est3" >"$f" <<'PY'
import sys
rows=[l.rstrip('\n').split('\t') for l in open(sys.argv[1])]
real, est, real2, est2, est3 = sys.argv[2:7]
t={r[0]: float(r[3]) for r in rows}
for e, res, key, ms in rows:
    print(f"count.{e}\t{e}：结果 {res}，执行计划使用的索引 {key}")
same=[t['COUNT(*)'], t['COUNT(1)'], t['COUNT(id)']]
print(f"timing.same\tCOUNT(*)、COUNT(1)、COUNT(id) 的耗时（5 次中位数）相差在 1.5 倍以内={max(same) / min(same) < 1.5}")
print(f"timing.remark\tCOUNT(remark)（没有索引、要读整行）比 COUNT(*) 慢={t['COUNT(remark)'] > t['COUNT(*)'] * 1.5}")
print(f"table_rows.estimate\t50 万行时 TABLE_ROWS 估算与真实行数相差在 10% 以内={abs(int(est) - int(real)) / int(real) < 0.1}（具体值见 table-rows.txt）")
print(f"table_rows.after-delete\t删掉 10 万行、ANALYZE 之后，TABLE_ROWS 与真实的 {real2} 行相差在 10% 以内={abs(int(est3) - int(real2)) / int(real2) < 0.1}")
open(sys.argv[1].replace('raw.tsv', 'table-rows.txt'), 'w').write(f"真实行数 {real}，TABLE_ROWS {est}\n删除 10 万行后真实行数 {real2}，ANALYZE 前 TABLE_ROWS {est2}，ANALYZE 后 {est3}\n")
PY
cp "$OUT/raw.tsv" "$OUT/timings.tsv"; rm "$OUT/raw.tsv"
mysql_exec "$P" -N -e "SELECT CONCAT('mysql_version: ', VERSION())" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
count.COUNT(*)	COUNT(*)：结果 500000，执行计划使用的索引 idx_status
count.COUNT(1)	COUNT(1)：结果 500000，执行计划使用的索引 idx_status
count.COUNT(id)	COUNT(id)：结果 500000，执行计划使用的索引 idx_status
count.COUNT(coupon_id)	COUNT(coupon_id)：结果 166666，执行计划使用的索引 idx_coupon
count.COUNT(remark)	COUNT(remark)：结果 100000，执行计划使用的索引 全表
timing.same	COUNT(*)、COUNT(1)、COUNT(id) 的耗时（5 次中位数）相差在 1.5 倍以内=True
timing.remark	COUNT(remark)（没有索引、要读整行）比 COUNT(*) 慢=True
table_rows.estimate	50 万行时 TABLE_ROWS 估算与真实行数相差在 10% 以内=True（具体值见 table-rows.txt）
table_rows.after-delete	删掉 10 万行、ANALYZE 之后，TABLE_ROWS 与真实的 400000 行相差在 10% 以内=True
EXPECTED
log "全部通过，输出在 $OUT"
