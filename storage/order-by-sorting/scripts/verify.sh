#!/usr/bin/env bash
# ORDER BY：混合升降序与降序索引、排序缓冲区不够时溢出到磁盘、LIMIT 走优先队列、同值行分页重复与遗漏（MySQL 8.4.11）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-order-by-sorting），结束时删除；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-order-by-sorting
trap 'mysql_down "$P" >/dev/null 2>&1 || true' EXIT
mysql_up "$P" >/dev/null 2>&1
mysql_exec "$P" labs <schema/setup.sql >/dev/null
q() { mysql_exec "$P" labs -N -e "$1"; }
f="$OUT/output.tsv"; : >"$f"; d="$OUT/details.txt"; : >"$d"

# 一、索引方向：同一个 LIMIT 10，分别强制使用升序索引与 (category, score DESC) 索引
n=0
for spec in "idx_cat_score|category, score" "idx_cat_score|category DESC, score DESC" "idx_cat_score|category, score DESC" "idx_cat_score_desc|category, score DESC"; do
  n=$((n + 1)); ix=${spec%%|*}; ob=${spec#*|}
  sql="SELECT id FROM products FORCE INDEX ($ix) ORDER BY $ob LIMIT 10"
  extra=$(q "EXPLAIN $sql" | awk -F'\t' '{print $NF}')
  reads=$(q "FLUSH STATUS; $sql; SHOW SESSION STATUS LIKE 'Handler_read%';" | awk -F'\t' '/^Handler_read/{s+=$2} END{print s}')
  ids=$(q "SELECT GROUP_CONCAT(id) FROM ($sql) t")
  printf '%s\t%s\t%s\t%s\t%s\n' "$ix" "$ob" "$extra" "$reads" "$ids" >>"$OUT/index-direction.tsv"
  printf 'direction.%s\t索引 %s、ORDER BY %s：Extra=%s，读取索引记录 %s 条\n' "$n" "$ix" "$ob" "$extra" "$reads" >>"$f"
done
r3=$(sed -n 3p "$OUT/index-direction.tsv" | cut -f5); r4=$(sed -n 4p "$OUT/index-direction.tsv" | cut -f5)
echo "direction.same	两种方式得到的前 10 行相同=$([ "$r3" = "$r4" ] && echo True || echo False)" >>"$f"

# 二、排序缓冲区：按没有索引的 score 排序，取前 10 行与取最后 10 行，缓冲区 32 KB 与 64 MB
for buf in 32768 67108864; do for lim in "LIMIT 10" "LIMIT 199990, 10"; do
  res=$(q "SET SESSION sort_buffer_size=$buf; SET optimizer_trace='enabled=on'; FLUSH STATUS; SELECT id, name FROM products ORDER BY score $lim; SHOW SESSION STATUS LIKE 'Sort_merge_passes'; SELECT IFNULL(JSON_EXTRACT(TRACE,'\$**.filesort_priority_queue_optimization.chosen'),'[false]'), JSON_EXTRACT(TRACE,'\$**.filesort_summary') FROM information_schema.OPTIMIZER_TRACE;" | tail -2)
  passes=$(echo "$res" | sed -n 1p | cut -f2); pq=$(echo "$res" | sed -n 2p | cut -f1 | tr -d '[]')
  summary=$(echo "$res" | sed -n 2p | cut -f2)
  spilled=$(echo "$summary" | grep -o '"num_initial_chunks_spilled_to_disk": [0-9]*' | grep -o '[0-9]*$')
  peak=$(echo "$summary" | grep -o '"peak_memory_used": [0-9]*' | grep -o '[0-9]*$')
  echo "sort_buffer_size=$buf $lim: $summary Sort_merge_passes=$passes" >>"$d"
  label=$([ "$buf" = 32768 ] && echo "32KB" || echo "64MB"); which=$([ "$lim" = "LIMIT 10" ] && echo "前 10 行" || echo "最后 10 行")
  printf 'sort.%s.%s\t缓冲区 %s、取%s：优先队列=%s，溢出到磁盘的块 %s 个，合并 %s 趟\n' "$label" "$([ "$lim" = "LIMIT 10" ] && echo head || echo tail)" "$label" "$which" "$pq" "$spilled" "$passes" >>"$f"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$lim" "$pq" "$spilled" "$passes" "$peak" >>"$OUT/sort-buffer.tsv"
done; done

# 三、同值行分页：price 只有 50 个值，每个值 4000 行；按 price 翻 10 页、每页 10 行
pages() { local all=""; for i in 0 1 2 3 4 5 6 7 8 9; do all="$all $(q "SELECT id FROM products ORDER BY $1 LIMIT $((i * 10)), 10" | tr '\n' ' ')"; done; echo "$all" | tr ' ' '\n' | grep -v '^$' | sort -u | wc -l | tr -d ' '; }
u1=$(pages "price"); u2=$(pages "price, id")
p1=$(q "SELECT GROUP_CONCAT(id) FROM (SELECT id FROM products ORDER BY price LIMIT 10) t")
p2=$(q "SELECT GROUP_CONCAT(id) FROM (SELECT id FROM (SELECT id FROM products ORDER BY price LIMIT 100) x LIMIT 10) t")
echo "ORDER BY price LIMIT 10: $p1" >>"$d"; echo "ORDER BY price LIMIT 100 的前 10 行: $p2" >>"$d"
echo "ties.price	ORDER BY price 翻 10 页共 100 行，不同的 id 只有 $u1 个" >>"$f"
echo "ties.limit	ORDER BY price 的 LIMIT 10 与 LIMIT 100 的前 10 行相同=$([ "$p1" = "$p2" ] && echo True || echo False)" >>"$f"
echo "ties.fixed	ORDER BY price, id 翻 10 页，不同的 id 有 $u2 个" >>"$f"

mysql_exec "$P" -N -e "SELECT CONCAT('mysql_version: ', VERSION())" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
direction.1	索引 idx_cat_score、ORDER BY category, score：Extra=Using index，读取索引记录 11 条
direction.2	索引 idx_cat_score、ORDER BY category DESC, score DESC：Extra=Backward index scan; Using index，读取索引记录 11 条
direction.3	索引 idx_cat_score、ORDER BY category, score DESC：Extra=Using index; Using filesort，读取索引记录 200002 条
direction.4	索引 idx_cat_score_desc、ORDER BY category, score DESC：Extra=Using index，读取索引记录 11 条
direction.same	两种方式得到的前 10 行相同=True
sort.32KB.head	缓冲区 32KB、取前 10 行：优先队列=true，溢出到磁盘的块 0 个，合并 0 趟
sort.32KB.tail	缓冲区 32KB、取最后 10 行：优先队列=false，溢出到磁盘的块 736 个，合并 121 趟
sort.64MB.head	缓冲区 64MB、取前 10 行：优先队列=true，溢出到磁盘的块 0 个，合并 0 趟
sort.64MB.tail	缓冲区 64MB、取最后 10 行：优先队列=false，溢出到磁盘的块 0 个，合并 0 趟
ties.price	ORDER BY price 翻 10 页共 100 行，不同的 id 只有 54 个
ties.limit	ORDER BY price 的 LIMIT 10 与 LIMIT 100 的前 10 行相同=False
ties.fixed	ORDER BY price, id 翻 10 页，不同的 id 有 100 个
EXPECTED
log "全部通过，输出在 $OUT"
