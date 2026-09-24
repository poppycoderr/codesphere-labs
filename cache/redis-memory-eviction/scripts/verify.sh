#!/usr/bin/env bash
# Redis 内存满了会怎样：淘汰策略、扫描污染下的 LRU 与 LFU、过期回收的时间序列、碎片与主动碎片整理
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Redis 容器，2 CPU、1 GB 内存；约 3 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
rc() { "${C[@]}" exec -T redis redis-cli "$@" | tr -d '\r'; }
pipe() { python3 scripts/gen.py "$@" | "${C[@]}" exec -T redis redis-cli --pipe 2>&1 | grep -E "^errors: " | tail -1; }  # 输出 errors 与 replies 计数
info() { rc INFO "${2:-everything}" | awk -F: -v k="$1" '$1 == k {print $2}'; }
fresh() { rc CONFIG SET maxmemory 0 >/dev/null; rc FLUSHALL SYNC >/dev/null; rc MEMORY PURGE >/dev/null; rc CONFIG RESETSTAT >/dev/null; sleep 0.5; }
errors() { sed -E 's/.*errors: ([0-9]+).*/\1/' <<<"$1"; }

"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null
rc INFO server | grep -E '^(redis_version|mem_allocator):' >"$OUT/server.txt"
for p in maxmemory maxmemory-policy maxmemory-samples lfu-log-factor lfu-decay-time active-expire-effort activedefrag \
         active-defrag-ignore-bytes active-defrag-threshold-lower active-defrag-cycle-min active-defrag-cycle-max; do
  printf '%s\t%s\n' "$p" "$(rc CONFIG GET "$p" | sed -n 2p)"
done >"$OUT/config-defaults.tsv"

log "场景一：maxmemory 8mb，写入 6 万条 200 字节的值，五种配置"
{
  echo -e "case\tpolicy\twrites\twrite_errors\tevicted_keys\tdbsize\tkeys_with_ttl\tfirst_15000_survived\tused_memory\tnext_set\tget_first_key"
  for c in noeviction allkeys-lru allkeys-lfu volatile-lru volatile-lru-mixed; do
    fresh; policy=${c%-mixed}
    rc CONFIG SET maxmemory-policy "$policy" >/dev/null; rc CONFIG SET maxmemory 8mb >/dev/null
    if [ "$c" = volatile-lru-mixed ]; then  # 1.5 万条常驻数据不带 TTL，之后 4.5 万条缓存数据带 1 小时 TTL
      e1=$(errors "$(pipe set k: 0 15000 200)"); e2=$(errors "$(pipe set k: 15000 60000 200 3600000)"); err=$((e1 + e2))
    else
      err=$(errors "$(pipe set k: 0 60000 200)")
    fi
    ttl=$(info db0 keyspace | sed -E 's/.*expires=([0-9]+).*/\1/')
    first=$(python3 -c 'print("EXISTS " + " ".join(f"k:{i}" for i in range(15000)))' | "${C[@]}" exec -T redis redis-cli | tr -d '\r')
    printf '%s\t%s\t60000\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$c" "$policy" "$err" "$(info evicted_keys stats)" "$(rc DBSIZE)" "${ttl:-0}" "$first" \
      "$(info used_memory memory)" "$(rc SET probe:after x | head -c 60)" "$(rc GET k:0 | head -c 12)"
  done
} >"$OUT/policies.tsv"

log "场景二：扫描污染。1000 个热 key 各读 30 次，2 秒后一次性写入 6 万个冷 key，统计热 key 的存活数"
{
  echo -e "policy\thot_keys\thot_reads_each\tcold_writes\tevicted_keys\thot_survived"
  for policy in allkeys-lru allkeys-lfu; do
    fresh; rc CONFIG SET maxmemory-policy "$policy" >/dev/null; rc CONFIG SET maxmemory 8mb >/dev/null
    pipe set hot: 0 1000 200 >/dev/null; pipe get hot: 0 1000 30 >/dev/null
    sleep 2  # LRU 时钟精度为 1 秒，拉开热 key 与扫描的访问时间
    pipe set cold: 0 60000 200 >/dev/null
    hot=$(python3 -c 'print("EXISTS " + " ".join(f"hot:{i}" for i in range(1000)))' | "${C[@]}" exec -T redis redis-cli | tr -d '\r')
    printf '%s\t1000\t30\t60000\t%s\t%s\n' "$policy" "$(info evicted_keys stats)" "$hot"
  done
} >"$OUT/scan-pollution.tsv"

sample_expiry() {  # sample_expiry <场景> <秒数>：在容器内每 0.2 秒采集一次键数、过期计数与内存（容器时钟），与写入并行
  "${C[@]}" exec -T redis sh -c "end=\$(( \$(date +%s) + $2 )); while [ \$(date +%s) -lt \$end ]; do
    echo \"@@ \$(date +%s.%N)\"; redis-cli INFO keyspace; redis-cli INFO stats | grep -E '^expired_(keys|stale_perc)';
    redis-cli INFO memory | grep -E '^used_memory(_rss)?:'; echo \"dbsize:\$(redis-cli DBSIZE)\"; sleep 0.2; done" | tr -d '\r' >"$OUT/expiry-$1-raw.txt"
}
expiry_case() {  # expiry_case <场景> <gen.py 参数...>：记录写入起止时间（容器时钟）并同时采样
  local name=$1; shift
  fresh; rc CONFIG SET maxmemory-policy noeviction >/dev/null
  sample_expiry "$name" 16 & local sampler=$!
  sleep 0.5
  echo "load_started $("${C[@]}" exec -T redis date +%s.%N | tr -d '\r')" >"$OUT/expiry-$name-load.txt"
  pipe "$@" >>"$OUT/expiry-$name-load.txt"
  echo "load_finished $("${C[@]}" exec -T redis date +%s.%N | tr -d '\r')" >>"$OUT/expiry-$name-load.txt"
  wait $sampler
  python3 scripts/expiry_tsv.py "$OUT/expiry-$name-raw.txt" "$OUT/expiry-$name-load.txt" >"$OUT/expiry-$name.tsv"
}
log "场景三：20 万个 key 在同一时刻（3 秒后）过期，与 3—9 秒均匀分散过期对照"
expiry_case same-ttl set exp: 0 200000 100 3000
rc INFO memory | grep -E '^(used_memory|used_memory_rss|mem_fragmentation_ratio|mem_fragmentation_bytes|allocator_allocated|allocator_active|allocator_resident|allocator_frag_ratio|allocator_frag_bytes|allocator_rss_ratio|allocator_rss_bytes|rss_overhead_ratio):' >"$OUT/memory-after-expiry.txt"
expiry_case jitter-ttl set exp: 0 200000 100 3000 6000

log "场景四：50 万个 64—448 字节的值，删除其中四分之三，再打开主动碎片整理"
fresh
pipe mixed frag: 500000 >/dev/null
rc INFO memory >"$OUT/fragmentation-loaded.txt"
pipe del-except frag: 500000 4 >/dev/null
rc INFO memory >"$OUT/fragmentation-deleted.txt"
rc CONFIG SET active-defrag-ignore-bytes 10mb >/dev/null
rc CONFIG SET activedefrag yes >"$OUT/activedefrag-set.txt"
echo -e "t_seconds\tactive_defrag_running\tactive_defrag_hits\tactive_defrag_misses\tallocator_frag_ratio\tallocator_frag_bytes\tmem_fragmentation_ratio\tused_memory\tused_memory_rss\tused_cpu_user" >"$OUT/defrag-timeline.tsv"
for i in $(seq 0 40); do
  rc INFO everything | awk -F: -v t="$i" '{v[$1] = $2} END {printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", t, v["active_defrag_running"], v["active_defrag_hits"], v["active_defrag_misses"], v["allocator_frag_ratio"], v["allocator_frag_bytes"], v["mem_fragmentation_ratio"], v["used_memory"], v["used_memory_rss"], v["used_cpu_user"]}' >>"$OUT/defrag-timeline.tsv"
  sleep 1
done
rc INFO memory >"$OUT/fragmentation-defragged.txt"
rc DBSIZE >"$OUT/fragmentation-dbsize.txt"

{ "${C[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2"; echo "mem_limit: 1g"; echo "command: redis-server --save '' --appendonly no"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
