#!/usr/bin/env bash
# 时间边界与窗口函数的语义：BETWEEN 的上界、会话时区、LAG 与缺失日期、连续天数、三种排名、默认窗口帧、ROLLUP 的 NULL、每组最新一行
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-sql-window），结束时删除；约 40 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-sql-window
trap 'mysql_down "$P" >/dev/null 2>&1 || true' EXIT
mysql_up "$P" >/dev/null 2>&1
mysql_exec "$P" labs <schema/setup.sql
mysql_exec "$P" labs -N <schema/queries.sql >"$OUT/output.tsv"
{
  echo "# 半开区间"
  mysql_exec "$P" labs -e "EXPLAIN SELECT COUNT(*) FROM t_orders WHERE created_at >= '2026-09-01' AND created_at < '2026-10-01'"
  echo "# 对列使用 DATE()"
  mysql_exec "$P" labs -e "EXPLAIN SELECT COUNT(*) FROM t_orders WHERE DATE(created_at) BETWEEN '2026-09-01' AND '2026-09-30'"
} >"$OUT/explain.txt"
mysql_exec "$P" -N -e "SELECT CONCAT('mysql_version: ', VERSION());" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)"
cat "$OUT/output.tsv" >&2
# 期望值按 schema/setup.sql 的数据手算（第三列是理由），不由另一条 SQL 得出
cut -f1,2 schema/expected.tsv >"$OUT/expected-values.tsv"
if ! diff "$OUT/expected-values.tsv" "$OUT/output.tsv" >"$OUT/diff.txt"; then cat "$OUT/diff.txt" >&2; fail "查询结果与手算期望值不一致"; fi
rm -f "$OUT/diff.txt" "$OUT/expected-values.tsv"
expect_line "$OUT/mysql-version.txt" "mysql_version: 8.4.11" "运行在 MySQL 8.4.11"
expect_regex "$OUT/explain.txt" "^1	SIMPLE	t_orders	NULL	range	idx_created_at	idx_created_at	" "半开区间：在索引上做范围扫描"
expect_regex "$OUT/explain.txt" "^1	SIMPLE	t_orders	NULL	index	NULL	idx_created_at	" "对列使用 DATE()：没有可用的范围，扫描整个索引"
log "全部通过：$(wc -l <"$OUT/output.tsv" | tr -d ' ') 条查询的结果与手算期望值一致，输出在 $OUT"
