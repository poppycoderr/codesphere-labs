#!/usr/bin/env bash
# 同一份 500 万行订单数据分别写入 MySQL 与 ClickHouse，核对三条查询的结果完全相同，再各预热 3 次、采样 7 次
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：两个容器各 2 CPU、3 GB 内存；约 3—5 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; rm -rf "${OUT:?}/queries"; mkdir -p "$OUT/queries"
COMPOSE=(docker compose -f compose.yaml)
my() { "${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password mysql mysql -uroot labs "$@"; }
ch() { "${COMPOSE[@]}" exec -T clickhouse clickhouse-client --user labs --password example_password --database labs "$@"; }
"${COMPOSE[@]}" up -d --wait >&2
log "造数：MySQL 与 ClickHouse 各 500 万行（约 2—3 分钟）"
my <schema/mysql.sql >/dev/null
ch --multiquery <schema/clickhouse.sql
log "预热 MySQL 缓冲池"
echo "SELECT COUNT(*), SUM(amount), SUM(LENGTH(remark)) FROM orders_big; SELECT COUNT(*) FROM orders_big FORCE INDEX (idx_customer_created) WHERE customer_id > 0;" | my >/dev/null

WARMUP=3; SAMPLES=7
for q in q1-status-aggregate q2-monthly-range q3-point-lookup; do
  d="$OUT/queries/$q"; mkdir -p "$d"
  mq=queries/$q.sql; [ -f "$mq" ] || mq=queries/$q.mysql.sql
  cq=queries/$q.sql; [ -f "$cq" ] || cq=queries/$q.clickhouse.sql
  cp "$mq" "$d/mysql.sql"; cp "$cq" "$d/clickhouse.sql"
  log "$q：MySQL 与 ClickHouse 各预热 $WARMUP 次、采样 $SAMPLES 次"
  my -N -B <"$mq" >"$d/mysql-result.tsv"
  ch --format TSV --output_format_decimal_trailing_zeros=1 <"$cq" >"$d/clickhouse-result.tsv"
  for _ in $(seq $WARMUP); do my -N <"$mq" >/dev/null; ch --format Null <"$cq"; done
  # MySQL：同一会话内用 NOW(6) 前后相减
  for i in $(seq $SAMPLES); do
    printf 'SET @t = NOW(6);\n%s\nSELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6)) / 1000;\n' "$(sed 's/$/;/' "$mq")" | my -N | tail -1
  done >"$d/mysql-millis.txt"
  echo "EXPLAIN ANALYZE $(cat "$mq")" | my -N -r >"$d/mysql-explain-analyze.txt"
  # ClickHouse：取 system.query_log 的 query_duration_ms、read_rows、read_bytes
  for i in $(seq $SAMPLES); do ch --query_id "labs-$q-$i-$$" --format Null <"$cq"; done
  ch -q "SYSTEM FLUSH LOGS"
  ch --format TSVWithNames -q "SELECT query_duration_ms, read_rows, read_bytes FROM system.query_log
        WHERE type = 'QueryFinish' AND query_id LIKE 'labs-$q-%-$$' ORDER BY event_time_microseconds" >"$d/clickhouse-query-log.tsv"
done

log "存储占用"
{
  echo "# MySQL（information_schema.tables，InnoDB 统计值）"
  echo "SELECT table_rows, ROUND(data_length / 1048576) AS data_mb, ROUND(index_length / 1048576) AS index_mb FROM information_schema.tables WHERE table_schema = 'labs' AND table_name = 'orders_big';" | my -t
  echo "# ClickHouse（system.columns，压缩后字节）"
  ch --format PrettyCompactNoEscapes -q "SELECT name, formatReadableSize(data_compressed_bytes) AS compressed, formatReadableSize(data_uncompressed_bytes) AS uncompressed FROM system.columns WHERE database = 'labs' AND table = 'orders_big'"
  ch --format PrettyCompactNoEscapes -q "SELECT formatReadableSize(sum(bytes_on_disk)) AS on_disk, sum(rows) AS rows FROM system.parts WHERE database = 'labs' AND table = 'orders_big' AND active"
} >"$OUT/storage.txt"
{ echo "SELECT VERSION(), @@innodb_buffer_pool_size DIV 1048576 AS buffer_pool_mb;" | my -t; echo "clickhouse: $(ch -q 'SELECT version()')"
  "${COMPOSE[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2（每个容器）"; echo "mem_limit: 3g（每个容器）"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11" "clickhouse: $(ch -q 'SELECT version()')"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
