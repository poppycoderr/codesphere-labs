#!/usr/bin/env bash
# 采集与查询无关的现场信息：版本、配置、表大小、数据分布、镜像 digest
# 用法：scripts/collect.sh <输出目录>
source "$(dirname "$0")/env.sh"
OUT="$1"; mkdir -p "$OUT"
sql -t >"$OUT/database-status.txt" <<'SQL'
SELECT VERSION() AS version, @@innodb_buffer_pool_size / 1024 / 1024 AS buffer_pool_mb,
       @@transaction_isolation AS isolation, @@optimizer_switch LIKE '%prefer_ordering_index=on%' AS prefer_ordering_index_on;
SELECT table_rows AS estimated_rows, ROUND(data_length / 1024 / 1024) AS data_mb, ROUND(index_length / 1024 / 1024) AS index_mb
FROM information_schema.tables WHERE table_schema = 'labs' AND table_name = 'task_event';
SELECT index_name, seq_in_index, column_name, cardinality
FROM information_schema.statistics WHERE table_schema = 'labs' AND table_name = 'task_event' ORDER BY index_name, seq_in_index;
SQL
sql -t >"$OUT/dataset.txt" <<'SQL'
SELECT COUNT(*) AS total_rows, MIN(id) AS min_id, MAX(id) AS max_id FROM task_event;
SELECT state, event_type, deleted, COUNT(*) AS n, MIN(id) AS first_id, MAX(id) AS last_id
FROM task_event GROUP BY state, event_type, deleted ORDER BY state, event_type, deleted;
SELECT COUNT(*) AS target_rows, MIN(id) AS first_target_id
FROM task_event WHERE state = 0 AND event_type IN ('PAY', 'REFUND') AND deleted = 0;
SQL
{
  echo "image: $(docker compose -f compose.yaml config --images)"
  echo "cpus: 2"
  echo "mem_limit: 2g"
  echo "config: config/mysql.cnf"
} >"$OUT/container.txt"
