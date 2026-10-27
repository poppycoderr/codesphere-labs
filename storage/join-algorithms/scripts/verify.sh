#!/usr/bin/env bash
# JOIN 的执行方式：索引嵌套循环、索引在哪一侧决定驱动表、哈希连接、关闭哈希连接后的嵌套循环、全量连接时哈希更快、连接缓冲区不够时写临时文件（MySQL 8.4.11）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-join-algorithms），结束时删除；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-join-algorithms
trap 'mysql_down "$P" >/dev/null 2>&1 || true' EXIT
mysql_up "$P" >/dev/null 2>&1
mysql_exec "$P" labs <schema/setup.sql >/dev/null
q() { mysql_exec "$P" labs -N -e "$1"; }
f="$OUT/output.tsv"; : >"$f"; plans="$OUT/plans.txt"; : >"$plans"; : >"$OUT/timings.tsv"

# 每个场景：结果、计划里的连接算子、第一个读的表、内层循环次数、耗时（EXPLAIN ANALYZE 顶层实际耗时，runs 次取中位数）
run() {
  local key=$1 runs=$2 sql=$3 plan t times=()
  echo "== $key: $sql" >>"$plans"
  for i in $(seq "$runs"); do
    plan=$(q "EXPLAIN ANALYZE $sql" | sed 's/\\n/\n/g')
    t=$(echo "$plan" | head -1 | grep -o 'actual time=[0-9.e+-]*\.\.[0-9.e+-]*' | sed 's/.*\.\.//')
    times+=("$(python3 -c "print(float('$t'))")")
  done
  echo "$plan" >>"$plans"
  local median; median=$(printf '%s\n' "${times[@]}" | sort -g | sed -n "$(( (runs + 1) / 2 ))p")
  local result; result=$(q "$sql")
  local join; join=$(echo "$plan" | grep -oE 'Nested loop inner join|Inner hash join' | head -1)
  local first; first=$(echo "$plan" | grep -oE 'Table scan on [a-z]+|Covering index scan on [a-z]+' | head -1 | awk '{print $NF}')
  # 哈希连接的树里先列出的是探测侧；实际先执行的是 Hash 下面的构建侧
  if [ "$join" = "Inner hash join" ]; then first=$(echo "$plan" | sed -n '/-> Hash$/,$p' | grep -oE 'Table scan on [a-z]+' | head -1 | awk '{print $NF}'); fi
  local loops; loops=$(echo "$plan" | grep -E 'lookup on|Table scan on o' | tail -1 | grep -o 'loops=[0-9]*' | tail -1 | cut -d= -f2)
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$key" "$result" "$join" "$first" "$loops" "$median" >>"$OUT/timings.tsv"
}
S1="SELECT SUM(o.amount) FROM customers c JOIN orders o ON o.customer_id = c.id WHERE c.city = 3"
S2="SELECT SUM(o.amount) FROM customers c JOIN orders o ON o.customer_ref = c.id WHERE c.city = 3"
S3="SELECT SUM(o.amount) FROM customers c JOIN orders o ON o.customer_ref = c.code WHERE c.city = 3"
S4="SELECT /*+ NO_BNL(c, o) */ SUM(o.amount) FROM customers c JOIN orders o ON o.customer_ref = c.code WHERE c.city = 3"
F1="SELECT SUM(o.amount) FROM customers c JOIN orders o ON o.customer_id = c.id"
F2="SELECT SUM(o.amount) FROM customers c JOIN orders o ON o.customer_ref = c.code"
run selective.index 5 "$S1"; run selective.other-side 5 "$S2"; run selective.hash 5 "$S3"; run selective.no-hash 1 "$S4"
run full.index 5 "$F1"; run full.hash 5 "$F2"

# 连接缓冲区：全量哈希连接，构建侧带上 name 列
for jb in 131072 262144 67108864; do
  n=$(q "SET SESSION join_buffer_size=$jb; FLUSH STATUS; SELECT SUM(o.amount + LENGTH(c.name)) FROM orders o JOIN customers c ON o.customer_ref = c.code; SHOW SESSION STATUS LIKE 'Created_tmp_files';" | tail -1 | cut -f2)
  printf '%s\t%s\n' "$jb" "$n" >>"$OUT/join-buffer.tsv"
done

python3 - "$OUT/timings.tsv" "$OUT/join-buffer.tsv" >"$f" <<'PY'
import sys
rows = {r[0]: r for r in (l.rstrip('\n').split('\t') for l in open(sys.argv[1]))}
ms = {k: float(r[5]) for k, r in rows.items()}
names = {"selective.index": "城市过滤 + orders.customer_id 有索引", "selective.other-side": "城市过滤 + 只有 customers.id 有索引",
         "selective.hash": "城市过滤 + 两侧都没有索引", "selective.no-hash": "同上，用 NO_BNL 关闭哈希连接",
         "full.index": "全量连接 + orders.customer_id 有索引", "full.hash": "全量连接 + 两侧都没有索引"}
for k, label in names.items():
    _, res, join, first, loops, _ = rows[k]
    how = f"用 {first} 建哈希表、扫描另一张表探测" if join == "Inner hash join" else f"先读 {first}，内层执行 {loops} 次"
    print(f"plan.{k}\t{label}：结果 {res}，{join}，{how}")
print(f"time.other-side\t只有另一侧有索引时比索引嵌套循环慢 10 倍以上={ms['selective.other-side'] > 10 * ms['selective.index']}")
print(f"time.hash-vs-index\t有过滤条件时，哈希连接比索引嵌套循环慢={ms['selective.hash'] > ms['selective.index']}")
print(f"time.no-hash\t关闭哈希连接后的嵌套循环比哈希连接慢 20 倍以上={ms['selective.no-hash'] > 20 * ms['selective.hash']}")
print(f"time.full\t全量连接时，哈希连接比索引嵌套循环快={ms['full.hash'] < ms['full.index']}")
jb = dict(l.rstrip('\n').split('\t') for l in open(sys.argv[2]))
print(f"buffer.small\tjoin_buffer_size=128KB 与 256KB 时写了临时文件={int(jb['131072']) > 0 and int(jb['262144']) > 0}")
print(f"buffer.large\tjoin_buffer_size=64MB 时临时文件 {jb['67108864']} 个")
PY
mysql_exec "$P" -N -e "SELECT CONCAT('mysql_version: ', VERSION())" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
plan.selective.index	城市过滤 + orders.customer_id 有索引：结果 1608000，Nested loop inner join，先读 c，内层执行 200 次
plan.selective.other-side	城市过滤 + 只有 customers.id 有索引：结果 1608000，Nested loop inner join，先读 o，内层执行 300000 次
plan.selective.hash	城市过滤 + 两侧都没有索引：结果 1608000，Inner hash join，用 c 建哈希表、扫描另一张表探测
plan.selective.no-hash	同上，用 NO_BNL 关闭哈希连接：结果 1608000，Nested loop inner join，先读 c，内层执行 200 次
plan.full.index	全量连接 + orders.customer_id 有索引：结果 149850000，Nested loop inner join，先读 c，内层执行 20000 次
plan.full.hash	全量连接 + 两侧都没有索引：结果 149850000，Inner hash join，用 c 建哈希表、扫描另一张表探测
time.other-side	只有另一侧有索引时比索引嵌套循环慢 10 倍以上=True
time.hash-vs-index	有过滤条件时，哈希连接比索引嵌套循环慢=True
time.no-hash	关闭哈希连接后的嵌套循环比哈希连接慢 20 倍以上=True
time.full	全量连接时，哈希连接比索引嵌套循环快=True
buffer.small	join_buffer_size=128KB 与 256KB 时写了临时文件=True
buffer.large	join_buffer_size=64MB 时临时文件 0 个
EXPECTED
log "全部通过，输出在 $OUT"
