#!/usr/bin/env bash
# 对每条查询：预热 3 次 → 结果校验和 → Handler 计数 → EXPLAIN FORMAT=JSON → EXPLAIN ANALYZE 采样 7 次
# 用法：scripts/run.sh <输出目录>
source "$(dirname "$0")/env.sh"
OUT="$1"
WARMUP="${WARMUP:-3}"
SAMPLES="${SAMPLES:-7}"
mkdir -p "$OUT"

dir_for() {
  case "$1" in
    00-*) echo "$OUT/before/$1" ;;
    1?-*) echo "$OUT/control/$1" ;;
    *)    echo "$OUT/after/$1" ;;
  esac
}

for f in queries/*.sql; do
  name=$(basename "$f" .sql)
  d=$(dir_for "$name"); mkdir -p "$d"
  q=$(cat "$f")
  log "$name：预热 $WARMUP 次"
  for _ in $(seq "$WARMUP"); do echo "$q;" | sql -N >/dev/null; done

  echo "$q;" | sql -N | cut -f1 >"$d/result-ids.txt"

  bp="SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads');"
  { echo "$bp"; echo "FLUSH STATUS;"; echo "$q;"; echo "SHOW SESSION STATUS LIKE 'Handler_read%';"; echo "$bp"; } \
    | sql -N >"$d/status.tmp"
  grep '^Handler_read' "$d/status.tmp" >"$d/handler-status.txt"
  grep '^Innodb_buffer_pool' "$d/status.tmp" | awk -F'\t' '
    { if ($1 in a) printf "%s_delta\t%d\n", $1, $2 - a[$1]; else a[$1] = $2 }' >"$d/buffer-pool.txt"
  rm "$d/status.tmp"

  echo "EXPLAIN FORMAT=JSON $q;" | sql -N -r >"$d/explain.json"

  log "$name：EXPLAIN ANALYZE 采样 $SAMPLES 次"
  : >"$d/explain-analyze.txt"
  for i in $(seq "$SAMPLES"); do
    { echo "-- sample $i"; echo "EXPLAIN ANALYZE $q;" | sql -N -r; } >>"$d/explain-analyze.txt"
  done
  cp "$f" "$d/query.sql"
done
