#!/usr/bin/env bash
# 在与 MySQL 共享网络的 JDK 21 容器中运行 ImportBench.java，断言写入速度的相对关系与批处理失败语义
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
COMPOSE=(docker compose -f compose.yaml)
sq() { "${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password mysql mysql -uroot labs "$@"; }
"${COMPOSE[@]}" up -d --wait >&2
CID=$("${COMPOSE[@]}" ps -q mysql)
echo "SET GLOBAL local_infile = ON;" | sq
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "运行写入对比（每种方式 3 次，约 2—3 分钟）"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/ImportBench.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/import-bench.txt"
echo "SELECT VERSION(), @@innodb_flush_log_at_trx_commit, @@sync_binlog, @@log_bin, @@max_allowed_packet;" | sq -t >"$OUT/server.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest，默认配置，开启 binlog）" "jdbc: $MYSQL_JDBC_JAR_COORD" "jdk_image: $JDK_IMAGE"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
