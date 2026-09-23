#!/usr/bin/env bash
# 表设计三个细节：逻辑删除的唯一约束、IP 地址存储与网段查询、热点行更新（JDK 21 + Connector/J）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
COMPOSE=(docker compose -f compose.yaml)
sq() { "${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password mysql mysql -uroot labs "$@"; }
"${COMPOSE[@]}" up -d --wait >&2
CID=$("${COMPOSE[@]}" ps -q mysql)

log "逻辑删除与唯一约束"
sq -t <schema/01-soft-delete.sql >"$OUT/soft-delete.txt" 2>&1
for t in b c; do
  echo "INSERT INTO service_record_$t (user_id, product_code) VALUES (1001, 'VIP');" | sq >>"$OUT/soft-delete-duplicate-$t.txt" 2>&1 || true
done

log "IP 地址：100 万行字符串与 VARBINARY(16)"
sq <schema/02-ip.sql >/dev/null
{
  echo "SELECT INET_ATON('192.0.2.235'), HEX(INET6_ATON('192.0.2.235')), HEX(INET6_ATON('2001:db8::1')), INET6_NTOA(INET6_ATON('2001:0db8:0000::0001')),
               HEX(INET6_ATON('::ffff:192.0.2.235')), IS_IPV4_MAPPED(INET6_ATON('::ffff:192.0.2.235'));" | sq -t
  echo "SELECT 'binary BETWEEN' AS method, COUNT(*) FROM access_log_bin WHERE ip BETWEEN INET6_ATON('10.1.0.0') AND INET6_ATON('10.1.255.255')
        UNION ALL SELECT 'string BETWEEN', COUNT(*) FROM access_log_str WHERE ip BETWEEN '10.1.0.0' AND '10.1.255.255'
        UNION ALL SELECT 'string LIKE', COUNT(*) FROM access_log_str WHERE ip LIKE '10.1.%'
        UNION ALL SELECT 'binary /20', COUNT(*) FROM access_log_bin WHERE ip BETWEEN INET6_ATON('10.1.0.0') AND INET6_ATON('10.1.15.255');" | sq -t
  echo "SELECT '10.1.100.1' < '10.1.99.1' AS string_compare, INET6_ATON('10.1.100.1') < INET6_ATON('10.1.99.1') AS binary_compare;" | sq -t
  echo "SELECT table_name, index_name, stat_value * @@innodb_page_size DIV 1024 AS index_kb FROM mysql.innodb_index_stats
        WHERE database_name = 'labs' AND table_name LIKE 'access_log_%' AND index_name = 'idx_ip' AND stat_name = 'size' ORDER BY table_name;" | sq -t
} >"$OUT/ip.txt"

log "热点行：1、16、64 个线程，同一行与分散更新；64 线程下 4、16 个桶（约 1—2 分钟）"
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/HotRow.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/hot-row.tsv"
echo "SELECT VERSION(), @@innodb_flush_log_at_trx_commit, @@sync_binlog, @@log_bin;" | sq -t >"$OUT/server.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest，默认配置，开启 binlog）" "jdbc: $MYSQL_JDBC_JAR_COORD" "jdk_image: $JDK_IMAGE"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
