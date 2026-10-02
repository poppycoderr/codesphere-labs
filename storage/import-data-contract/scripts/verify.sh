#!/usr/bin/env bash
# 导入的数据合同：INSERT IGNORE 的静默转换、暂存表与拒绝原因、对账、重跑、全量快照里的删除、迟到的旧版本
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-import-contract），结束时删除；约 40 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-import-contract
trap 'mysql_down "$P" >/dev/null 2>&1 || true' EXIT
mysql_up "$P" >/dev/null 2>&1
mysql_exec "$P" labs -N <schema/run.sql >"$OUT/output.tsv" 2>"$OUT/stderr.txt"
[ -s "$OUT/stderr.txt" ] || rm -f "$OUT/stderr.txt"
mysql_exec "$P" -N -e "SELECT CONCAT('mysql_version: ', VERSION()); SELECT CONCAT('sql_mode: ', @@sql_mode);" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)"
cat "$OUT/output.tsv" >&2
# 期望值按 schema/run.sql 里的数据手工推出（第三列是理由）
cut -f1,2 schema/expected.tsv | diff - "$OUT/output.tsv" || fail "结果与期望值不一致"
expect_line "$OUT/mysql-version.txt" "mysql_version: 8.4.11" "运行在 MySQL 8.4.11"
expect_regex "$OUT/mysql-version.txt" "^sql_mode: .*STRICT_TRANS_TABLES" "严格模式开启（INSERT IGNORE 仍会把错误降级）"
log "全部通过：$(wc -l <"$OUT/output.tsv" | tr -d ' ') 项结果与期望值一致，输出在 $OUT"
