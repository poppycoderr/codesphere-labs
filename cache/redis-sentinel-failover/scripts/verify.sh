#!/usr/bin/env bash
# Redis Sentinel 故障切换：部分同步与全量同步、primary 进程被杀、primary 网络分区（异步 / min-replicas-to-write / WAIT）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：3 个 Redis（各 1 CPU、512 MB）、3 个 Sentinel、1 个 JDK 21 客户端；每个场景重建拓扑，约 5 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
NET_DATA=csl-redis-sentinel_data NET_SIDE=csl-redis-sentinel_side
on() { local n=$1; shift; "${C[@]}" exec -T "$n" redis-cli "$@" | tr -d '\r'; }
sen() { "${C[@]}" exec -T s1 redis-cli -p 26379 "$@" | tr -d '\r'; }
cid() { "${C[@]}" ps -aq "$1"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
ev() { printf '%s\t%s\t%s\n' "$(now_ms)" "$2" "${3:-}" >>"$OUT/$1-script.tsv"; }
field() { awk -F: -v k="$1" '$1 == k {print $2}'; }

topology_up() {  # 重建拓扑并等待 Sentinel 发现 2 个 replica 与另外 2 个 Sentinel
  "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  "${C[@]}" up -d --wait >/dev/null 2>&1
  local i
  for i in $(seq 60); do
    local m; m=$(sen SENTINEL master m | paste - - | tr '\t' '=')
    grep -q '^num-slaves=2$' <<<"$m" && grep -q '^num-other-sentinels=2$' <<<"$m" && return 0
    sleep 1
  done
  fail "Sentinel 没有发现完整拓扑"
}
primary() { sen SENTINEL get-master-addr-by-name m | head -1 | sed 's/-data$//'; }
start_client() {  # start_client <场景> <WAIT 副本数>
  rm -f "$OUT/$1.stop"
  docker run --rm --network "$NET_SIDE" -v "$PWD:/w" -w /w "$JDK_IMAGE" java src/Failover.java "$OUT/$1" "$2" 200 >"$OUT/$1-client.log" 2>&1 &
  CLIENT=$!
  local i; for i in $(seq 60); do grep -q $'\tack\t' "$OUT/$1-writes.tsv" 2>/dev/null && break; sleep 0.5; done
  sleep 3
}
wait_client_on() {  # wait_client_on <场景> <节点>：等客户端在新 primary 上写入成功
  local i; for i in $(seq 120); do
    awk -F'\t' -v h="$2" '$3 == h && ($2 == "ack" || $2 == "unconfirmed") {found = 1; exit} END {exit !found}' "$OUT/$1-writes.tsv" && return 0; sleep 0.5
  done
  fail "$1：客户端没有在 $2 上恢复写入"
}
snapshot() {  # snapshot <场景> <阶段>
  local n; for n in r1 r2 r3; do
    echo "## $n"; on "$n" INFO replication | grep -E '^(role|master_host|master_link_status|master_replid|master_replid2|master_repl_offset|second_repl_offset|repl_backlog_size|repl_backlog_histlen|slave[0-9]|connected_slaves|min_slaves_good_slaves)' || echo "unreachable"
    on "$n" INFO stats | grep -E '^(sync_full|sync_partial_ok|sync_partial_err):' || true
  done >"$OUT/$1-replication-$2.txt"
}
finish() {  # finish <场景>：停止客户端，导出最终 primary 上的全部序号与日志
  sleep 3; touch "$OUT/$1.stop"; wait "$CLIENT" || true
  local p; p=$(primary)
  echo "$p" >"$OUT/$1-final-primary.txt"
  "${C[@]}" exec -T "$p" redis-cli --scan --pattern 'f:*' | tr -d '\r' | sed 's/^f://' | sort -n >"$OUT/$1-final-seqs.txt"
  local n; for n in r1 r2 r3 s1 s2 s3; do "${C[@]}" logs --no-color --timestamps "$n" 2>&1 | sed "s/^/$n /"; done \
    | grep -E 'sdown|odown|elected|switch-master|failover|selected|promoted|convert|Partial|Full|PSYNC|MASTER <-> REPLICA|Connection with|NOREPL|timeout|backlog|replid' >"$OUT/$1-server-events.log" || true
}

log "场景零：replica 暂停，source 在 repl-timeout（3 秒）后断开它，再写入 200 KB，4 秒后恢复：默认 1 MB backlog 与最小 16 KB backlog"
topology_up
for bl in 1mb 16kb; do
  R=resync-backlog-$bl
  on r1 CONFIG SET repl-timeout 3 >/dev/null; on r1 CONFIG SET repl-backlog-size "$bl" >/dev/null
  start_client $R 0
  before=$(on r1 INFO stats | grep -E '^sync_(full|partial_ok):' | paste -sd' ' -)
  ev $R pause_r3; docker pause "$(cid r3)" >/dev/null
  # 等 source 因 repl-timeout 断开 r3 之后再写入 200 KB；断开之前写入的数据会留在 r3 的 TCP 接收缓冲区里，恢复后仍能读到
  for i in $(seq 40); do [ "$(on r1 INFO replication | field connected_slaves)" = 1 ] && break; sleep 0.25; done; ev $R source_dropped_r3
  head -c 204800 /dev/zero | tr '\0' x | on r1 -x SET filler >/dev/null; ev $R filler_200kb_written
  sleep 4; docker unpause "$(cid r3)" >/dev/null; ev $R unpause_r3
  for i in $(seq 40); do [ "$(on r3 INFO replication | field master_link_status)" = up ] && break; sleep 0.5; done; sleep 2
  after=$(on r1 INFO stats | grep -E '^sync_(full|partial_ok):' | paste -sd' ' -)
  printf '%s\tbefore=%s\tafter=%s\tbacklog=%s\n' "$R" "$before" "$after" "$(on r1 INFO replication | grep -E '^repl_backlog_(size|histlen):' | paste -sd' ' -)" >>"$OUT/resync.tsv"
  finish $R
done

log "场景一：primary 进程被 SIGKILL（异步复制）"
R=kill-primary
topology_up; start_client $R 0; snapshot $R before
ev $R fault_injected "docker kill r1"; docker kill "$(cid r1)" >/dev/null
for i in $(seq 60); do [ "$(primary)" != r1 ] && break; sleep 0.5; done
NEW=$(primary); ev $R new_primary "$NEW"
wait_client_on $R "$NEW"
docker start "$(cid r1)" >/dev/null; ev $R old_primary_restarted
for i in $(seq 60); do [ "$(on r1 INFO replication | field role)" = slave ] && [ "$(on r1 INFO replication | field master_link_status)" = up ] && break; sleep 0.5; done
snapshot $R after; finish $R

partition() {  # partition <场景> <WAIT 副本数> [primary 的额外配置...]
  local R=$1 w=$2; shift 2
  topology_up
  local n; for n in r1 r2 r3; do [ $# -gt 0 ] && on "$n" CONFIG SET "$@" >/dev/null; done
  [ $# -gt 0 ] && on r1 CONFIG GET "$1" | paste -sd' ' - >"$OUT/$R-config.txt"
  start_client "$R" "$w"; snapshot "$R" before
  ev "$R" fault_injected "r1 与 data 网络断开，客户端仍经 side 网络连着 r1"
  docker network disconnect "$NET_DATA" "$(cid r1)"
  local i; for i in $(seq 60); do [ "$(primary)" != r1 ] && break; sleep 0.5; done
  NEW=$(primary); ev "$R" new_primary "$NEW"
  wait_client_on "$R" "$NEW"; sleep 2
  on r1 DBSIZE | sed 's/^/old_primary_dbsize_before_heal=/' >"$OUT/$R-old-primary.txt"
  on r1 INFO replication | grep -E '^(role|min_slaves_good_slaves|connected_slaves):' >>"$OUT/$R-old-primary.txt"
  ev "$R" partition_healed; docker network connect --alias r1-data "$NET_DATA" "$(cid r1)"
  for i in $(seq 90); do [ "$(on r1 INFO replication | field role)" = slave ] && [ "$(on r1 INFO replication | field master_link_status)" = up ] && break; sleep 0.5; done
  ev "$R" old_primary_rejoined "role=$(on r1 INFO replication | field role)"
  snapshot "$R" after; finish "$R"
}
log "场景二：primary 网络分区，异步复制"
partition partition-async 0
log "场景三：primary 网络分区，min-replicas-to-write 1、min-replicas-max-lag 2"
partition partition-min-replicas 0 min-replicas-to-write 1 min-replicas-max-lag 2
log "场景四：primary 网络分区，每次写入后 WAIT 1 100"
partition partition-wait 1

{ "${C[@]}" config --images | sort -u | sed 's/^/image: /'; echo "redis: 1 CPU、512m（每个）；sentinel: 0.5 CPU、128m（每个）"; echo "sentinel: config/sentinel.conf"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest，1 primary + 2 replicas + 3 Sentinel）" "jdk_image: $JDK_IMAGE"
rm -f "$OUT"/*.stop  # 客户端的停止标记，没有内容
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
