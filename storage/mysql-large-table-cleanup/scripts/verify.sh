#!/usr/bin/env bash
# 完整流程：启动容器 → 造数 → 一次性删除 → 锁范围探测 → 分批删除 → 主键区间删除 → 删除分区 → 空间回收 → 断言
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：2 CPU、3 GB 内存、约 3 GB 磁盘；每次运行都重新造数（场景会修改数据），约 6—10 分钟
source "$(dirname "$0")/env.sh"
OUT="${1:-build/run}"; mkdir -p "$OUT"
"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
log "启动 MySQL 8.4.11 容器（compose 项目 csl-large-table-cleanup）"
"${COMPOSE[@]}" up -d --wait >&2
log "建表并生成 300 万行 × 4 张表（约 3—5 分钟）"
sql <schema/01-schema.sql
t0=$(date +%s); sql <schema/02-seed.sql >/dev/null; log "造数用时 $(( $(date +%s) - t0 )) 秒"
sql <schema/03-procedures.sql

ibd() { "${COMPOSE[@]}" exec -T mysql sh -c "stat -c '%n %s' /var/lib/mysql/labs/$1.ibd" | sed 's#/var/lib/mysql/##'; }
# binlog_mark：切到新的 binlog 文件，输出文件名；binlog_since <文件>：从该文件起的总字节数与事务数（Xid 事件）
binlog_mark() { echo "FLUSH BINARY LOGS; SHOW BINARY LOG STATUS;" | sql -N | cut -f1; }
binlog_since() {
  local start="$1" files f bytes=0 xids=0 size
  files=$(echo "SHOW BINARY LOGS;" | sql -N | awk -v s="$start" '$1 >= s {print $1"\t"$2}')
  while IFS=$'\t' read -r f size; do
    bytes=$(( bytes + size ))
    xids=$(( xids + $(echo "SHOW BINLOG EVENTS IN '$f';" | sql -N | awk -F'\t' '$3 == "Xid"' | wc -l) ))
  done <<<"$files"
  echo "binlog_bytes	$bytes"; echo "transactions	$xids"
}
timed() { printf 'SET @t = NOW(6);\n%s;\nSELECT ROW_COUNT() AS affected, TIMESTAMPDIFF(MICROSECOND, @t, NOW(6)) AS micros;\n' "$1" | sql -N | tail -1; }

echo "SELECT VERSION(), @@innodb_buffer_pool_size DIV 1048576 AS buffer_pool_mb, @@binlog_format, @@binlog_row_image, @@sync_binlog, @@innodb_flush_log_at_trx_commit, @@max_binlog_size;
      SELECT COUNT(*), MIN(created_at), MAX(created_at) FROM event_log;
      SELECT COUNT(*) AS rows_before_cutoff FROM event_log WHERE created_at < '2025-04-11';" | sql -t >"$OUT/dataset.txt"
ibd event_log >>"$OUT/dataset.txt"

log "场景一：一次性删除 100 万行"
b=$(binlog_mark)
{ echo "DELETE FROM event_log WHERE created_at < '2025-04-11'"; echo "affected	micros"; timed "DELETE FROM event_log WHERE created_at < '2025-04-11'"; binlog_since "$b"; } >"$OUT/one-shot-delete.txt"

log "场景二：删除事务持有期间的锁范围（每个探测的锁等待超时 2 秒）"
probe_case() {
  local file="$OUT/$1.txt" del="$2"; shift 2
  local plan; plan=$(echo "EXPLAIN $del;" | sql -B)
  ( printf 'BEGIN;\n%s;\nSELECT "deleted", ROW_COUNT();\nDO SLEEP(40);\nROLLBACK;\n' "$del" | sql -n -N >"$file.a" 2>&1 ) &
  local i; for i in $(seq 120); do grep -q deleted "$file.a" 2>/dev/null && break; sleep 1; done
  { echo "# 会话 A：BEGIN; $del;（持有到 ROLLBACK）"; echo "# 会话 A 删除行数：$(grep deleted "$file.a" | cut -f2)"
    echo "# 执行计划："; echo "$plan" | sed 's/^/#   /'
    echo "# data_locks 汇总（持有期间）："
    echo "SELECT index_name, lock_mode, COUNT(*) FROM performance_schema.data_locks WHERE object_name = 'event_log_copy' AND lock_type = 'RECORD' GROUP BY index_name, lock_mode ORDER BY index_name, lock_mode;" | sql -N | sed 's/^/#   /'

    echo "# 会话 B 探测，innodb_lock_wait_timeout = 2"
    local p r
    for p in "$@"; do
      r=$(printf 'SET SESSION innodb_lock_wait_timeout = 2;\nSET @t = NOW(6);\n%s;\nSELECT CONCAT("完成 ", ROUND(TIMESTAMPDIFF(MICROSECOND, @t, NOW(6)) / 1000, 1), "ms");\n' "$p" \
        | sql -N 2>&1 | grep -o "ERROR 1205\|完成 .*" | head -1 || true)
      printf '%s\t%s\n' "$p" "$r"
    done; } >"$file"
  wait; rm -f "$file.a"
}
PROBES_IDX=(
  "UPDATE event_log_copy SET content = 'probe' WHERE id = 100"
  "UPDATE event_log_copy SET content = 'probe' WHERE id = 2000000"
  "INSERT INTO event_log_copy (biz_id, content, created_at) VALUES (0, 'probe', '2025-01-15 12:00:00')"
  "INSERT INTO event_log_copy (biz_id, content, created_at) VALUES (0, 'probe', '2025-02-01 00:00:00')"
  "INSERT INTO event_log_copy (biz_id, content, created_at) VALUES (0, 'probe', '2025-09-01 12:00:00')")
# 条件列有索引，但 31 万行占全表约 10%，优化器按成本选择全表扫描
probe_case lock-probe-indexed "DELETE FROM event_log_copy WHERE created_at < '2025-02-01'" "${PROBES_IDX[@]}"
echo "DELETE FROM event_log_copy WHERE biz_id = 0; UPDATE event_log_copy SET content = RPAD(CONCAT('event-', id, '-'), 150, 'x') WHERE id IN (100, 2000000);" | sql
# 同一条删除用优化器提示强制走 idx_created
probe_case lock-probe-index-hint "DELETE /*+ INDEX(event_log_copy idx_created) */ FROM event_log_copy WHERE created_at < '2025-02-01'" "${PROBES_IDX[@]}"
echo "DELETE FROM event_log_copy WHERE biz_id = 0; UPDATE event_log_copy SET content = RPAD(CONCAT('event-', id, '-'), 150, 'x') WHERE id IN (100, 2000000);" | sql
probe_case lock-probe-no-index "DELETE FROM event_log_copy WHERE biz_id <= 310000" \
  "UPDATE event_log_copy SET content = 'probe' WHERE id = 100" \
  "UPDATE event_log_copy SET content = 'probe' WHERE id = 2000000" \
  "INSERT INTO event_log_copy (biz_id, content, created_at) VALUES (0, 'probe', '2025-01-15 12:00:00')" \
  "INSERT INTO event_log_copy (biz_id, content, created_at) VALUES (0, 'probe', '2025-09-01 12:00:00')"
# 探测中成功的插入与更新会留在表里，删掉它们，让后续场景的数据与 event_log 一致
echo "DELETE FROM event_log_copy WHERE biz_id = 0; UPDATE event_log_copy SET content = RPAD(CONCAT('event-', id, '-'), 150, 'x') WHERE id IN (100, 2000000);" | sql

log "场景三：按条件 LIMIT 分批删除，每批 10,000 行"
ibd event_log_copy >"$OUT/file-size.txt"
b=$(binlog_mark)
BATCH_LOG="CREATE TEMPORARY TABLE batch_log (batch INT PRIMARY KEY, deleted INT NOT NULL, micros BIGINT NOT NULL)"
sql -t >"$OUT/batch-delete.tmp" <<SQL
$BATCH_LOG;
SET @t = NOW(6);
CALL batch_delete('2025-04-11', 10000);
SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6)) AS total_micros;
SELECT COUNT(*) AS batches, SUM(deleted > 0) AS non_empty_batches, SUM(deleted) AS deleted, MAX(micros) AS max_batch_micros, ROUND(AVG(micros)) AS avg_batch_micros FROM batch_log;
SELECT '---per-batch---';
SELECT batch, deleted, micros FROM batch_log ORDER BY batch;
SQL
{ echo "CALL batch_delete('2025-04-11', 10000)"; sed '/---per-batch---/,$d' "$OUT/batch-delete.tmp"; binlog_since "$b"; } >"$OUT/batch-delete.txt"
sed -n '/---per-batch---/,$p' "$OUT/batch-delete.tmp" | grep -v -- '---per-batch---' >"$OUT/batch-delete-per-batch.txt"; rm "$OUT/batch-delete.tmp"

log "场景四：删除之后的文件大小与 OPTIMIZE TABLE"
sleep 10  # 等待 purge 回收已删除记录
ibd event_log_copy >>"$OUT/file-size.txt"
echo "optimize_micros	$(timed "OPTIMIZE TABLE event_log_copy" | cut -f2)" >>"$OUT/file-size.txt"
echo "OPTIMIZE TABLE event_log_copy;" | sql -t >"$OUT/optimize-message.txt" 2>&1 || true
ibd event_log_copy >>"$OUT/file-size.txt"

log "场景五：按主键区间分批删除，表尾有一条补写的旧数据"
echo "INSERT INTO event_log_range (biz_id, content, created_at) VALUES (0, 'late-arrival', '2025-01-05 00:00:00');" | sql
{ echo "# 在表尾插入一条 created_at = 2025-01-05 的补写数据后，按 MIN(id)..MAX(id) 每 10,000 个主键一批删除"
  echo "SELECT MIN(id), MAX(id), COUNT(*) FROM event_log_range WHERE created_at < '2025-04-11';" | sql -t
  printf '%s;\nCALL range_delete(%s, 10000);\nSELECT COUNT(*) AS batches, SUM(deleted = 0) AS empty_batches, SUM(deleted) AS deleted FROM batch_log;\n' "$BATCH_LOG" "'2025-04-11'" | sql -t; } >"$OUT/range-delete.txt"

log "场景六：删除分区"
b=$(binlog_mark)
{ echo "SELECT partition_name, table_rows FROM information_schema.partitions WHERE table_schema = 'labs' AND table_name = 'event_log_p' ORDER BY partition_ordinal_position;" | sql -t
  echo "SELECT COUNT(*) AS rows_in_p2025q1 FROM event_log_p PARTITION (p2025q1);" | sql -t
  echo "SHOW BINARY LOG STATUS;" | sql -N | awk '{print "binlog_position_before\t"$2}'
  echo "drop_micros	$(timed "ALTER TABLE event_log_p DROP PARTITION p2025q1" | cut -f2)"
  echo "SHOW BINARY LOG STATUS;" | sql -N | awk '{print "binlog_position_after\t"$2}'
  echo "SHOW BINLOG EVENTS IN '$b';" | sql -N | awk -F'\t' '$3 == "Query" {print "binlog_query\t"$6}' | grep -v "^binlog_query	BEGIN"
  echo "SELECT COUNT(*) AS rows_left FROM event_log_p;" | sql -t; } >"$OUT/drop-partition.txt"

{ echo "image: $("${COMPOSE[@]}" config --images)"; echo "cpus: 2"; echo "mem_limit: 3g"; echo "config: config/mysql.cnf"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest）"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
