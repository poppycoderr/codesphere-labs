#!/usr/bin/env bash
# JOIN 之后的结果粒度：一对多扇出、两个子表互相放大、DISTINCT 修不了、先聚合再连接、四种计数、外连接后过滤、三种人均、NOT IN 遇到 NULL、空集
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-sql-grain），结束时删除；约 40 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-sql-grain
trap 'mysql_down "$P" >/dev/null 2>&1 || true' EXIT
mysql_up "$P" >/dev/null 2>&1
mysql_exec "$P" labs <schema/setup.sql
mysql_exec "$P" labs -N <schema/queries.sql >"$OUT/output.tsv"
mysql_exec "$P" -N -e "SELECT CONCAT('mysql_version: ', VERSION()); SELECT CONCAT('sql_mode: ', @@sql_mode);" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)"
cat "$OUT/output.tsv" >&2
# 期望值是按 schema/setup.sql 的数据手算的（第三列是算式），不由另一条 SQL 得出
cut -f1,2 schema/expected.tsv >"$OUT/expected-values.tsv"
if ! diff "$OUT/expected-values.tsv" "$OUT/output.tsv" >"$OUT/diff.txt"; then cat "$OUT/diff.txt" >&2; fail "查询结果与手算期望值不一致"; fi
rm -f "$OUT/diff.txt" "$OUT/expected-values.tsv"
expect_line "$OUT/mysql-version.txt" "mysql_version: 8.4.11" "运行在 MySQL 8.4.11"
log "全部通过：$(wc -l <"$OUT/output.tsv" | tr -d ' ') 条查询的结果与手算期望值一致，输出在 $OUT"
