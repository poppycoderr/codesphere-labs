#!/usr/bin/env bash
# Redis Cluster：hash slot 与 hash tag、跨 slot 限制、MOVED、手动迁移 slot 时的 ASK、redis-cli --cluster reshard、
# 大 key 迁移的阻塞、slot 均匀但 key 与内存不均、primary 故障与 replica 提升。业务负载使用 Jedis 5.2.0 的 JedisCluster
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：6 个 Redis 节点（各 1 CPU、768 MB）与 JDK 21 客户端容器；约 5 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
NET=csl-redis-cluster_default
cid() { "${C[@]}" ps -aq "$1"; }
on() { local n=$1; shift; "${C[@]}" exec -T "$n" redis-cli "$@" | tr -d '\r'; }
ip() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(cid "$1")"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
JARS=""
for coord in redis.clients:jedis:5.2.0 org.apache.commons:commons-pool2:2.12.0 org.json:json:20240303 com.google.code.gson:gson:2.11.0 org.slf4j:slf4j-api:1.7.36; do
  JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"
done
JAVA=(docker run --rm --network "$NET" -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$JDK_IMAGE" java -cp "${JARS#:}")
declare_names() {  # 生成 IP 到服务名的映射，便于阅读 CLUSTER NODES
  local n; for n in n1 n2 n3 n4 n5 n6; do echo "$(ip $n) $n"; done >"$OUT/.names"
}
name_of() { awk -v a="${1%%:*}" '$1 == a {print $2}' "$OUT/.names"; }
nodes_named() { on "$1" CLUSTER NODES | python3 -c '
import sys
names = dict(l.split() for l in open(sys.argv[1]))
for line in sys.stdin:
    f = line.split()
    if not f: continue
    ip = f[1].split(":")[0]
    print(names.get(ip, ip), f[0][:8], f[2].replace("myself,", ""), (names.get(ip) and f[3][:8]) or f[3][:8], f[7], " ".join(f[8:]))' "$OUT/.names"; }
owner() {  # owner <slot>：该 slot 的 primary 服务名
  on n1 CLUSTER NODES | python3 -c '
import sys
slot = int(sys.argv[1]); names = dict(l.split() for l in open(sys.argv[2]))
for line in sys.stdin:
    f = line.split()
    if "master" not in f[2] or "fail" in f[2]: continue
    for r in f[8:]:
        if r.startswith("["): continue
        a, _, b = r.partition("-"); b = b or a
        if int(a) <= slot <= int(b): print(names[f[1].split(":")[0]]); sys.exit()' "$1" "$OUT/.names"
}
node_id() { on "$1" CLUSTER MYID; }
wait_ok() { local i n; for i in $(seq 60); do for n in "$@"; do [ "$(on "$n" CLUSTER INFO | awk -F: '$1 == "cluster_state" {print $2}')" = ok ] || continue 2; done; return 0; done; fail "集群状态没有变为 ok"; }

"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
declare_names
log "组建集群：3 primary + 3 replica"
on n1 --cluster create $(for n in n1 n2 n3 n4 n5 n6; do printf '%s:6379 ' "$(ip $n)"; done) --cluster-replicas 1 --cluster-yes >"$OUT/cluster-create.txt" 2>&1
sleep 3; wait_ok n1 n2 n3 n4 n5 n6
nodes_named n1 >"$OUT/cluster-nodes-initial.txt"
on n1 CLUSTER INFO >"$OUT/cluster-info-initial.txt"
on n1 CLUSTER SHARDS >"$OUT/cluster-shards-initial.txt"

log "场景一：hash slot、hash tag 与跨 slot 操作"
{
  for k in user:1001:profile user:1001:orders '{user:1001}:profile' '{user:1001}:orders'; do printf 'keyslot\t%s\t%s\n' "$k" "$(on n1 CLUSTER KEYSLOT "$k")"; done
  on n1 -c SET user:1001:profile p >/dev/null; on n1 -c SET user:1001:orders o >/dev/null
  printf 'redis-cli_mget_different_slots\t%s\n' "$(on n1 -c MGET user:1001:profile user:1001:orders 2>&1 | head -1)"
} >"$OUT/slots.tsv"
"${JAVA[@]}" src/ClusterClient.java crossslot "$OUT/crossslot.tsv" >"$OUT/crossslot.log" 2>&1

log "场景二：向不负责该 slot 的节点发请求，得到 MOVED"
KEY_SLOT=$(on n1 CLUSTER KEYSLOT user:1001:profile); OWN=$(owner "$KEY_SLOT")
OTHER=n1; [ "$OWN" = n1 ] && OTHER=n2
{ printf 'key\tuser:1001:profile\nslot\t%s\nowner\t%s\nasked\t%s\n' "$KEY_SLOT" "$OWN" "$OTHER"
  printf 'reply_without_-c\t%s\n' "$(on "$OTHER" GET user:1001:profile 2>&1)"; } >"$OUT/moved.tsv"

log "场景三：手动迁移一个 slot（每批 100 个 key），迁移期间观察 ASK，JedisCluster 持续读写"
S=$(on n1 CLUSTER KEYSLOT '{mig}'); A=$(owner "$S")
B=$(for n in n1 n2 n3; do [ "$n" != "$A" ] && [ "$(on $n INFO replication | awk -F: '$1 == "role" {print $2}')" = master ] && echo $n; done | head -1 || true)
rm -f "$OUT/migrate.stop"
"${JAVA[@]}" src/ClusterClient.java workload "$OUT/migrate" mig >"$OUT/migrate-client.log" 2>&1 & WL=$!
sleep 6
{
  printf 'slot\t%s\nsource\t%s\ntarget\t%s\nkeys_before\t%s\n' "$S" "$A" "$B" "$(on "$A" CLUSTER COUNTKEYSINSLOT "$S")"
  printf 'started_at_ms\t%s\n' "$(now_ms)"
  on "$B" CLUSTER SETSLOT "$S" IMPORTING "$(node_id "$A")" | sed 's/^/setslot_importing\t/'
  on "$A" CLUSTER SETSLOT "$S" MIGRATING "$(node_id "$B")" | sed 's/^/setslot_migrating\t/'
  batches=0; first=""
  while :; do
    keys=$(on "$A" CLUSTER GETKEYSINSLOT "$S" 100 | tr '\n' ' ')
    [ -z "${keys// /}" ] && break
    [ -z "$first" ] && first=$(awk '{print $1}' <<<"$keys")
    on "$A" MIGRATE "$(ip "$B")" 6379 "" 0 5000 KEYS $keys >/dev/null
    batches=$((batches + 1))
    if [ "$batches" = 1 ]; then
      printf 'get_migrated_key_at_source\t%s\n' "$(on "$A" GET "$first" 2>&1)"
      printf 'get_migrated_key_at_target_without_asking\t%s\n' "$(on "$B" GET "$first" 2>&1)"
      printf 'get_migrated_key_at_target_with_asking\t%s\n' "$(printf 'ASKING\nGET %s\n' "$first" | "${C[@]}" exec -T "$B" redis-cli | tr -d '\r' | paste -sd' ' -)"
      printf 'cluster_nodes_during_migration\t%s\n' "$(nodes_named "$A" | grep -E '\[' | paste -sd'|' -)"
    fi
    sleep 0.2
  done
  printf 'batches\t%s\n' "$batches"
  for n in n1 n2 n3 n4 n5 n6; do on "$n" CLUSTER SETSLOT "$S" NODE "$(node_id "$B")" >/dev/null 2>&1 || true; done
  printf 'finished_at_ms\t%s\n' "$(now_ms)"
  printf 'keys_after_source\t%s\nkeys_after_target\t%s\n' "$(on "$A" CLUSTER COUNTKEYSINSLOT "$S")" "$(on "$B" CLUSTER COUNTKEYSINSLOT "$S")"
  printf 'get_at_old_owner_after\t%s\n' "$(on "$A" GET "$first" 2>&1)"
} >"$OUT/migrate.tsv"
sleep 5; touch "$OUT/migrate.stop"; wait "$WL"

log "场景四：redis-cli --cluster reshard 迁移 1000 个 slot，JedisCluster 持续读写"
total_keys() { local s=0 n; for n in n1 n2 n3 n4 n5 n6; do [ "$(on $n INFO replication | awk -F: '$1 == "role" {print $2}')" = master ] && s=$((s + $(on $n DBSIZE))); done; echo $s; }
rm -f "$OUT/reshard.stop"
"${JAVA[@]}" src/ClusterClient.java workload "$OUT/reshard" rs >"$OUT/reshard-client.log" 2>&1 & WL=$!
sleep 8
FROM=$(owner 0); TO=$(owner 16383)
echo "keys_before	$(total_keys)" >"$OUT/reshard.tsv"
printf 'from\t%s\nto\t%s\nstarted_at_ms\t%s\n' "$FROM" "$TO" "$(now_ms)" >>"$OUT/reshard.tsv"
on n1 --cluster reshard "$(ip n1):6379" --cluster-from "$(node_id "$FROM")" --cluster-to "$(node_id "$TO")" --cluster-slots 1000 --cluster-yes >"$OUT/reshard-output.txt" 2>&1
printf 'finished_at_ms\t%s\n' "$(now_ms)" >>"$OUT/reshard.tsv"
sleep 5; touch "$OUT/reshard.stop"; wait "$WL"
echo "keys_after	$(total_keys)" >>"$OUT/reshard.tsv"
on n1 --cluster check "$(ip n1):6379" >"$OUT/reshard-check.txt" 2>&1 || true

log "场景五：迁移一个 50 万字段的大 Hash 与同一 slot 的小 key，对比源节点上探测请求的延迟"
BS=$(on n1 CLUSTER KEYSLOT '{big}'); BA=$(owner "$BS")
BB=$(for n in n1 n2 n3 n4 n5 n6; do [ "$n" != "$BA" ] && [ "$(on $n INFO replication | awk -F: '$1 == "role" {print $2}')" = master ] && echo $n; done | head -1 || true)
python3 - <<'PY' | "${C[@]}" exec -T "$BA" redis-cli --pipe >/dev/null
import sys
out = sys.stdout.buffer
for s in range(0, 500000, 1000):
    args = [b"HSET", b"{big}:hash"] + [x for i in range(s, s + 1000) for x in (b"f%d" % i, b"value-%d" % i)]
    out.write(b"*%d\r\n" % len(args) + b"".join(b"$%d\r\n%s\r\n" % (len(a), a) for a in args))
PY
{
  printf 'slot\t%s\nsource\t%s\ntarget\t%s\n' "$BS" "$BA" "$BB"
  printf 'big_hash_fields\t%s\nbig_hash_memory_usage\t%s\n' "$(on "$BA" HLEN '{big}:hash')" "$(on "$BA" MEMORY USAGE '{big}:hash')"
  on "$BA" CONFIG SET slowlog-log-slower-than 1000 >/dev/null; on "$BA" SLOWLOG RESET >/dev/null
  on "$BB" CLUSTER SETSLOT "$BS" IMPORTING "$(node_id "$BA")" >/dev/null
  on "$BA" CLUSTER SETSLOT "$BS" MIGRATING "$(node_id "$BB")" >/dev/null
  "${C[@]}" exec -T "$BA" sh -c 'redis-cli --latency-history -i 1 --raw 2>/dev/null & P=$!; sleep 2; echo "migrate_started $(date +%s%3N)"; redis-cli MIGRATE '"$(ip "$BB")"' 6379 "{big}:hash" 0 60000; echo "migrate_finished $(date +%s%3N)"; sleep 2; kill $P; echo' \
    | tr -d '\r' | sed 's/^/source_probe\t/'
  printf 'slowlog_on_source\t%s\n' "$(on "$BA" SLOWLOG GET 5 | paste -sd' ' -)"
  for n in n1 n2 n3 n4 n5 n6; do on "$n" CLUSTER SETSLOT "$BS" NODE "$(node_id "$BB")" >/dev/null 2>&1 || true; done
  printf 'big_hash_on_target\t%s\n' "$(on "$BB" HLEN '{big}:hash')"
} >"$OUT/big-key-migrate.tsv"

log "场景六：slot 数均匀，但 hash tag 把 5 万个 key 固定在一个 slot"
HOT_SLOT=$(on n1 CLUSTER KEYSLOT '{hot}'); HOT_OWNER=$(owner "$HOT_SLOT")  # 先算好：docker exec 会读走管道的标准输入
python3 - <<'PY' | "${C[@]}" exec -T "$HOT_OWNER" redis-cli --pipe >/dev/null
import sys
out = sys.stdout.buffer
for i in range(50000):
    k, v = b"{hot}:%d" % i, b"x" * 200
    out.write(b"*3\r\n$3\r\nSET\r\n$%d\r\n%s\r\n$%d\r\n%s\r\n" % (len(k), k, len(v), v))
PY
{
  echo -e "node\trole\tslots\tdbsize\tused_memory\tcmdstat_set_calls"
  for n in n1 n2 n3 n4 n5 n6; do
    role=$(on $n INFO replication | awk -F: '$1 == "role" {print $2}')
    [ "$role" = master ] || continue
    slots=$(on n1 CLUSTER NODES | python3 -c '
import sys
myip = sys.argv[1]; total = 0
for line in sys.stdin:
    f = line.split()
    if f[1].split(":")[0] != myip: continue
    for r in f[8:]:
        if r.startswith("["): continue
        a, _, b = r.partition("-"); total += int(b or a) - int(a) + 1
print(total)' "$(ip $n)")
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$n" "$role" "$slots" "$(on $n DBSIZE)" "$(on $n INFO memory | awk -F: '$1 == "used_memory" {print $2}')" \
      "$(on $n INFO commandstats | sed -nE 's/^cmdstat_set:calls=([0-9]+).*/\1/p')"
  done
} >"$OUT/skew.tsv"
printf 'hot_slot\t%s\thot_owner\t%s\thot_slot_keys\t%s\n' "$HOT_SLOT" "$HOT_OWNER" "$(on "$HOT_OWNER" CLUSTER COUNTKEYSINSLOT "$HOT_SLOT")" >>"$OUT/skew.tsv"

log "场景七：primary 进程被 SIGKILL，replica 提升，JedisCluster 每秒 200 次写入"
nodes_named n1 >"$OUT/failover-nodes-before.txt"
VICTIM=$(owner 8000)
SURVIVOR=$(for n in n1 n2 n3 n4 n5 n6; do [ "$n" != "$VICTIM" ] && echo $n; done | head -1 || true)
rm -f "$OUT/failover.stop"
"${JAVA[@]}" src/ClusterClient.java writer "$OUT/failover" >"$OUT/failover-client.log" 2>&1 & WR=$!
sleep 6
VICTIM_IP=$(ip "$VICTIM")
printf 'victim\t%s\nkilled_at_ms\t%s\n' "$VICTIM" "$(now_ms)" >"$OUT/failover.tsv"
docker kill "$(cid "$VICTIM")" >/dev/null
echo -e "at_ms\tcluster_state\tvictim_flags" >"$OUT/failover-state.tsv"
for i in $(seq 60); do
  printf '%s\t%s\t%s\n' "$(now_ms)" "$(on "$SURVIVOR" CLUSTER INFO | awk -F: '$1 == "cluster_state" {print $2}')" \
    "$(on "$SURVIVOR" CLUSTER NODES | awk -v a="$VICTIM_IP" 'index($2, a ":") == 1 {print $3}')" >>"$OUT/failover-state.tsv"
  sleep 0.5
done
touch "$OUT/failover.stop"; wait "$WR"
nodes_named "$SURVIVOR" >"$OUT/failover-nodes-after.txt"
for n in n1 n2 n3 n4 n5 n6; do
  [ "$n" = "$VICTIM" ] && continue
  [ "$(on $n INFO replication | awk -F: '$1 == "role" {print $2}')" = master ] && "${C[@]}" exec -T "$n" redis-cli --scan --pattern 'fo:*' | tr -d '\r'
done | sed 's/^fo://' | sort -n >"$OUT/failover-final-seqs.txt"
"${C[@]}" logs --no-color --timestamps 2>&1 | grep -E 'FAIL message|Failover|failover|Cluster state changed|marking node|election|Promot|configEpoch' >"$OUT/failover-server-events.log" || true
rm -f "$OUT/.names"

{ "${C[@]}" config --images | sort -u | sed 's/^/image: /'; echo "nodes: 6，各 1 CPU、768m，cluster-node-timeout 5000"; echo "jdk_image: $JDK_IMAGE"; echo "jedis: 5.2.0"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest，3 primary + 3 replica）" "jdk_image: $JDK_IMAGE" "jedis: 5.2.0"
rm -f "$OUT"/*.stop  # 客户端的停止标记，没有内容
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
