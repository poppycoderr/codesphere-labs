#!/usr/bin/env bash
# 故障切换与读一致性：异步分区后崩溃、半同步与暂停的 applier、半同步超时退化、重试歧义、写后读、旧 source 重新接入
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：3 个 MySQL 容器各 2 CPU、1.5 GB 内存；每个场景重建拓扑，约 8—10 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
source scripts/topology.sh
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
client() { docker run --rm --network csl-failover_client -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$JDK_IMAGE" \
  java -cp "/cache/m2/$(basename "$DRIVER")" src/Failover.java "$@" 2>&1 | grep -v '^WARN\|^Loading class' || true; }
start_writer() { echo source >"$OUT/$1-target"; client write "$OUT" "$1" & WRITER=$!; sleep 3; }
route_to() { echo "$2" >"$OUT/$1-target"; ev "$1" routing_changed "target=$2"; }
finish() {  # finish <场景> <新 source>：等首笔新写入，停止客户端，导出新 source 上的订单序号
  local i; for i in $(seq 60); do grep -q first_write_after_failover "$OUT/$1-client.tsv" 2>/dev/null && break; sleep 0.5; done
  sleep 2; touch "$OUT/$1-stop"; wait "$WRITER"
  echo "SELECT seq FROM labs.orders WHERE request_id LIKE '$1-%' ORDER BY seq;" | on "$2" -N >"$OUT/$1-new-source-seqs.txt"
  ev "$1" verification_data_exported "new_source=$2"
}

log "场景一：异步复制。两个 replica 先与 source 分区，3 秒后 source 崩溃"
R=async-partition
topology_up; client setup "$OUT" source; wait_synced
start_writer $R
ev $R partition_started
docker network disconnect "$NET_REPL" "$(cid replica1)"; docker network disconnect "$NET_REPL" "$(cid replica2)"
ev $R partition "replica1、replica2 与 source 的复制网络断开，客户端仍连着 source"
sleep 3
docker kill -s KILL "$(cid source)" >/dev/null; ev $R fault_injected "SIGKILL source"
docker network connect "$NET_REPL" "$(cid replica1)"; docker network connect "$NET_REPL" "$(cid replica2)"
detect $R; candidates $R
promote $R replica1
echo "STOP REPLICA;" | on replica2; replicate_from replica2 replica1
route_to $R replica1
finish $R replica1

log "场景六：旧 source 重启后直接作为 replica 接回（沿用场景一的拓扑）"
docker start "$(cid source)" >/dev/null; "${COMPOSE[@]}" up -d --wait source >/dev/null 2>&1
{
  echo "old_source_executed	$(echo "SELECT @@global.gtid_executed;" | on source -N | tr -d '\n')"
  echo "new_source_executed	$(echo "SELECT @@global.gtid_executed;" | on replica1 -N | tr -d '\n')"
  new=$(echo "SELECT @@global.gtid_executed;" | on replica1 -N | tr -d '\n')
  echo "old_is_subset_of_new	$(echo "SELECT GTID_SUBSET(@@global.gtid_executed, '$new');" | on source -N)"
  echo "old_only_transactions	$(echo "SELECT GTID_SUBTRACT(@@global.gtid_executed, '$new');" | on source -N | tr -d '\n')"
  echo "SET PERSIST super_read_only = ON;" | on source
  replicate_from source replica1
  sleep 5
  echo "SELECT WAIT_FOR_EXECUTED_GTID_SET('$(echo "SELECT @@global.gtid_executed;" | on replica1 -N | tr -d '\n')', 30);" | on source -N >/dev/null
  echo "after_rejoin_replica_status	$(echo "SHOW REPLICA STATUS\G" | on source | grep -E 'Replica_(IO|SQL)_Running:' | tr -s ' ' | tr '\n' ' ')"
  echo "after_rejoin_worker_error	$(echo "SELECT LAST_ERROR_MESSAGE FROM performance_schema.replication_applier_status_by_worker WHERE LAST_ERROR_NUMBER <> 0 LIMIT 1;" | on source -N | tr -d '\n')"
  echo "old_source_orders	$(echo "SELECT COUNT(*) FROM labs.orders;" | on source -N)"
  echo "new_source_orders	$(echo "SELECT COUNT(*) FROM labs.orders;" | on replica1 -N)"
  echo "old_source_checksum	$(echo "CHECKSUM TABLE labs.orders;" | on source -N | cut -f2)"
  echo "new_source_checksum	$(echo "CHECKSUM TABLE labs.orders;" | on replica1 -N | cut -f2)"
} >"$OUT/rejoin-old-source.tsv"

log "场景二：半同步（等 1 个 replica ACK）。replica2 停止接收，replica1 继续接收但暂停应用，4 秒后 source 崩溃"
R=semisync-applier-paused
topology_up; semisync_on 10000; client setup "$OUT" source; wait_synced
echo "SELECT @@rpl_semi_sync_source_wait_point, @@rpl_semi_sync_source_wait_for_replica_count, @@rpl_semi_sync_source_timeout;" | on source -t >"$OUT/$R-semisync-config.txt"
start_writer $R
echo "STOP REPLICA IO_THREAD;" | on replica2; ev $R replica2_receiver_stopped
echo "STOP REPLICA SQL_THREAD;" | on replica1; ev $R replica1_applier_paused "replica1 继续接收并发送 ACK，但不应用"
sleep 4
echo "SHOW GLOBAL STATUS LIKE 'Rpl_semi_sync_source_%';" | on source -N >"$OUT/$R-semisync-status-before-fault.txt"
docker kill -s KILL "$(cid source)" >/dev/null; ev $R fault_injected "SIGKILL source"
detect $R; candidates $R
echo "SELECT seq FROM labs.orders WHERE request_id LIKE '$R-%' ORDER BY seq;" | on replica1 -N >"$OUT/$R-replica1-before-relay-apply-seqs.txt"
promote $R replica1
echo "STOP REPLICA;" | on replica2; replicate_from replica2 replica1
route_to $R replica1
finish $R replica1

log "场景三：半同步超时（2 秒）后退化为异步；同时观察重试歧义"
R=semisync-timeout
topology_up; semisync_on 2000; client setup "$OUT" source; wait_synced
start_writer $R
ev $R partition_started
docker network disconnect "$NET_REPL" "$(cid replica1)"; docker network disconnect "$NET_REPL" "$(cid replica2)"
ev $R partition "两个 replica 与 source 的复制网络断开"
client ambiguous "$OUT"
echo "SHOW GLOBAL STATUS LIKE 'Rpl_semi_sync_source_%';" | on source -N >"$OUT/$R-semisync-status-before-fault.txt"
sleep 2
docker kill -s KILL "$(cid source)" >/dev/null; ev $R fault_injected "SIGKILL source"
docker network connect "$NET_REPL" "$(cid replica1)"; docker network connect "$NET_REPL" "$(cid replica2)"
detect $R; candidates $R
promote $R replica1
echo "STOP REPLICA;" | on replica2; replicate_from replica2 replica1
route_to $R replica1
finish $R replica1

log "场景四：写后读的四种策略（replica2 配置 SOURCE_DELAY = 1）"
topology_up; client setup "$OUT" source; wait_synced
echo "STOP REPLICA; CHANGE REPLICATION SOURCE TO SOURCE_DELAY = 1; START REPLICA;" | on replica2
client reads "$OUT"

{ "${COMPOSE[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2（每个容器）"; echo "mem_limit: 1536m（每个容器）"; echo "config: config/common.cnf"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest，1 source + 2 replicas，异步与半同步）" "jdbc: $MYSQL_JDBC_JAR_COORD" "jdk_image: $JDK_IMAGE"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
