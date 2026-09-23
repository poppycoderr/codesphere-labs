#!/usr/bin/env bash
# 完整流程：启动容器 → 造数 → 按索引状态分阶段采集执行计划 → 汇总与断言
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：2 CPU、2 GB 内存；不占用宿主机端口；约 1 分钟（不含拉取镜像）
source "$(dirname "$0")/env.sh"
OUT="${1:-build/run}"
rm -rf "${OUT:?}/plans"; mkdir -p "$OUT/plans"
log "启动 MySQL 8.4.11 容器（compose 项目 csl-explain-plans）"
"${COMPOSE[@]}" up -d --wait >&2
log "建表并生成 100,000 行"
sql <schema/01-schema.sql
sql <schema/02-seed.sql >/dev/null
echo "SET GLOBAL innodb_monitor_enable = 'module_icp';" | sql

# plan <名称> <SQL> [采样次数]：预热 3 次，保存 EXPLAIN（制表符分隔与 JSON）、Handler 计数和 EXPLAIN ANALYZE
plan() {
  local name="$1" q="$2" samples="${3:-1}" d="$OUT/plans/$1"
  mkdir -p "$d"
  echo "$q;" >"$d/query.sql"
  for _ in 1 2 3; do echo "$q;" | sql -N >/dev/null; done
  echo "EXPLAIN $q;" | sql -B >"$d/explain.tsv"
  echo "EXPLAIN FORMAT=JSON $q;" | sql -N -r >"$d/explain.json"
  { echo "FLUSH STATUS;"; echo "$q;"; echo "SHOW SESSION STATUS LIKE 'Handler_read%';"; } | sql -N | grep '^Handler_read' >"$d/handler.txt"
  # 索引条件下推（ICP）在存储引擎内过滤，Handler 计数只包含过滤后的行；用 InnoDB 的 ICP 计数器记录引擎实际检查的索引记录
  { echo "SET GLOBAL innodb_monitor_reset = 'module_icp';"; echo "$q;"
    echo "SELECT name, count_reset FROM information_schema.innodb_metrics WHERE subsystem = 'icp' ORDER BY name;"; } | sql -N | grep '^icp_' >"$d/icp.txt"
  : >"$d/explain-analyze.txt"
  local i
  for i in $(seq "$samples"); do
    { echo "-- sample $i"; echo "EXPLAIN ANALYZE $q;" | sql -N -r; } >>"$d/explain-analyze.txt"
  done
}
indexes() { echo "SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
  WHERE table_schema = 'labs' AND table_name = 'orders' GROUP BY index_name ORDER BY index_name;" | sql -N >"$OUT/plans/$1/indexes.txt"; }

log "阶段一：只有 idx_customer_created 与 idx_phone"
plan a01-ref-customer      "SELECT * FROM orders WHERE customer_id = 42"
plan a02-all-created       "SELECT * FROM orders WHERE created_at >= '2026-06-01'"
plan a03-range-two-columns "SELECT * FROM orders WHERE customer_id = 42 AND created_at >= '2026-06-01'"
plan a04-covering          "SELECT id, created_at FROM orders WHERE customer_id = 42 AND created_at >= '2026-06-01'"
plan a05-sort-amount       "SELECT * FROM orders WHERE customer_id = 42 ORDER BY amount DESC LIMIT 10"
plan a06-sort-created      "SELECT * FROM orders WHERE customer_id = 42 ORDER BY created_at DESC LIMIT 10"
plan a07-phone-string      "SELECT * FROM orders WHERE phone = '13800000042'"
plan a08-phone-number      "SELECT * FROM orders WHERE phone = 13800000042"
plan a09-top-paid          "SELECT customer_id, COUNT(*) FROM orders WHERE status = 'PAID' GROUP BY customer_id ORDER BY COUNT(*) DESC LIMIT 5" 7
plan a10-date-function     "SELECT * FROM orders WHERE DATE(created_at) = '2026-06-01'"
plan a11-date-range        "SELECT * FROM orders WHERE created_at >= '2026-06-01' AND created_at < '2026-06-02'"
plan a12-column-arithmetic "SELECT * FROM orders WHERE customer_id + 1 = 43"
plan a13-like-suffix       "SELECT * FROM orders WHERE phone LIKE '%0000042'"
plan a14-like-suffix-cover "SELECT id, phone FROM orders WHERE phone LIKE '%0000042'"
plan a15-like-prefix       "SELECT * FROM orders WHERE phone LIKE '1380000004%'"
plan a16-or-both-indexed   "SELECT * FROM orders WHERE customer_id = 42 OR phone = '13800000042'"
plan a17-or-one-unindexed  "SELECT * FROM orders WHERE customer_id = 42 OR amount = 10.99"
plan a18-not-equal         "SELECT * FROM orders WHERE customer_id != 42"
indexes a18-not-equal

log "阶段二：加 idx_status_customer (status, customer_id)"
echo "ALTER TABLE orders ADD KEY idx_status_customer (status, customer_id); ANALYZE TABLE orders;" | sql >/dev/null
plan b01-top-paid-covering "SELECT customer_id, COUNT(*) FROM orders WHERE status = 'PAID' GROUP BY customer_id ORDER BY COUNT(*) DESC LIMIT 5" 7
indexes b01-top-paid-covering

log "阶段三：去掉 idx_status_customer，加 idx_created (created_at)"
echo "ALTER TABLE orders DROP KEY idx_status_customer, ADD KEY idx_created (created_at); ANALYZE TABLE orders;" | sql >/dev/null
plan c01-date-range-indexed "SELECT * FROM orders WHERE created_at >= '2026-06-01' AND created_at < '2026-06-02'"
plan c02-date-function      "SELECT * FROM orders WHERE DATE(created_at) = '2026-06-01'"
indexes c02-date-function

log "阶段四：去掉 idx_created，加低区分度的 idx_status (status)"
echo "ALTER TABLE orders DROP KEY idx_created, ADD KEY idx_status (status); ANALYZE TABLE orders;" | sql >/dev/null
plan d01-status-index      "SELECT * FROM orders FORCE INDEX (idx_status) WHERE status = 'PAID'" 7
plan d02-status-table-scan "SELECT * FROM orders IGNORE INDEX (idx_status) WHERE status = 'PAID'" 7
indexes d02-status-table-scan

log "阶段五：联合索引列顺序 (status, created_at) 与 (created_at, status)"
Q5="SELECT * FROM orders WHERE status = 'PAID' AND created_at >= '2026-06-01' ORDER BY created_at"
echo "ALTER TABLE orders DROP KEY idx_status, ADD KEY idx_status_created (status, created_at); ANALYZE TABLE orders;" | sql >/dev/null
plan e01-equality-first "$Q5" 7
indexes e01-equality-first
echo "ALTER TABLE orders DROP KEY idx_status_created, ADD KEY idx_created_status (created_at, status); ANALYZE TABLE orders;" | sql >/dev/null
plan e02-range-first "SELECT * FROM orders FORCE INDEX (idx_created_status) WHERE status = 'PAID' AND created_at >= '2026-06-01' ORDER BY created_at" 7
indexes e02-range-first

echo "SELECT VERSION(), @@innodb_buffer_pool_size DIV 1048576 AS buffer_pool_mb, @@transaction_isolation;
      SELECT COUNT(*), COUNT(DISTINCT customer_id), MIN(created_at), MAX(created_at) FROM orders;
      SELECT status, COUNT(*) FROM orders GROUP BY status ORDER BY status;" | sql -t >"$OUT/dataset.txt"
{ echo "image: $("${COMPOSE[@]}" config --images)"; echo "cpus: 2"; echo "mem_limit: 2g"; echo "config: config/mysql.cnf"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest）"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
