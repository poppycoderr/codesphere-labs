#!/usr/bin/env bash
# 1 source + 2 replicas：稳态基线、大事务、并行回放对照、replica 上的查询负载、复制链路静默中断
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：3 个容器各 2 CPU、1 GB 内存；约 5 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
source scripts/topology.sh
OUT="${1:-build/run}"; rm -rf "${OUT:?}"/*.tsv; mkdir -p "$OUT"
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
client() { docker run --rm --network csl-replication-lag_client -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$JDK_IMAGE" \
  java -cp "/cache/m2/$(basename "$DRIVER")" src/ReplicationLag.java "$@" 2>&1 | grep -v '^WARN\|^Loading class' || true; }

log "启动 1 source + 2 replicas，开启 GTID，配置复制"
topology_up
client setup "$OUT"
gtid=$(echo "SELECT @@global.gtid_executed;" | on source -N)
for r in replica1 replica2; do echo "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid', 120);" | on "$r" -N >/dev/null; done

log "记录拓扑与复制配置"
for n in source replica1 replica2; do
  echo "== $n"
  echo "SELECT @@server_id, @@server_uuid, @@gtid_mode, @@enforce_gtid_consistency, @@binlog_format, @@sync_binlog, @@innodb_flush_log_at_trx_commit,
               @@replica_parallel_workers, @@replica_preserve_commit_order, @@replica_net_timeout, @@super_read_only\G" | on "$n"
done >"$OUT/topology.txt"
on replica1 <<<"SHOW REPLICA STATUS\G" | grep -E "Source_Host|Source_Port|Auto_Position|Replica_IO_Running|Replica_SQL_Running|Source_SSL_Allowed|SQL_Delay" >>"$OUT/topology.txt"

log "场景一：稳态基线（12 秒）"
client baseline "$OUT"
log "场景二：source 上一个更新 30 万行的大事务"
client big-transaction "$OUT"
log "场景三：并行回放对照（独立行 / 同一热点行 × 1 / 4 个 worker）"
client parallel "$OUT"
log "场景四：replica 上同时运行重查询"
client busy-replica "$OUT"
echo "SET GLOBAL replica_parallel_workers = 4; STOP REPLICA SQL_THREAD; START REPLICA SQL_THREAD;" | on replica1

log "场景五：把 replica2 从复制网络断开 30 秒（客户端网络保持连通）"
client stall "$OUT" 60000 &
CLIENT_PID=$!
sleep 8
echo -e "network_disconnected\t$(date -u +%Y-%m-%dT%H:%M:%SZ)\treplica2 离开 repl 网络" >"$OUT/stall-network.tsv"
docker network disconnect csl-replication-lag_repl "$("${COMPOSE[@]}" ps -q replica2)"
sleep 30
docker network connect csl-replication-lag_repl "$("${COMPOSE[@]}" ps -q replica2)"
echo -e "network_reconnected\t$(date -u +%Y-%m-%dT%H:%M:%SZ)\treplica2 重新加入 repl 网络" >>"$OUT/stall-network.tsv"
wait "$CLIENT_PID"
on replica2 <<<"SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running|Replica_SQL_Running|Last_IO_Error|Seconds_Behind_Source" >"$OUT/stall-replica2-status-after.txt"

log "最终一致性：三个节点的 GTID 集合与业务表校验和"
gtid=$(echo "SELECT @@global.gtid_executed;" | on source -N)
for r in replica1 replica2; do echo "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid', 120);" | on "$r" -N >/dev/null; done
for n in source replica1 replica2; do
  printf '%s\t%s\n' "$n" "$(echo "SELECT @@global.gtid_executed; CHECKSUM TABLE labs.heartbeat, labs.accounts, labs.big;" | on "$n" -N | tr '\n' ' ')"
done >"$OUT/final-checksums.tsv"

{ "${COMPOSE[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2（每个容器）"; echo "mem_limit: 1g（每个容器）"; echo "config: config/common.cnf"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest，1 source + 2 replicas）" "jdbc: $MYSQL_JDBC_JAR_COORD" "jdk_image: $JDK_IMAGE"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
