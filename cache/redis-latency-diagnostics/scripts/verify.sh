#!/usr/bin/env bash
# Redis 延迟诊断：探测客户端每毫秒一次 GET，依次注入大 key 删除、集中过期、长 Lua、KEYS、自动 BGSAVE 的 fork、
# 慢订阅者的输出缓冲区积压，对照 SLOWLOG、LATENCY 与客户端延迟；最后测 pipeline 批次大小
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Redis 容器（2 CPU、2 GB）与 JDK 21 客户端容器；约 4 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
X() { "${C[@]}" exec -T redis "$@"; }
rc() { X redis-cli "$@" | tr -d '\r'; }
pipe() { python3 scripts/gen.py "$@" | "${C[@]}" exec -T redis redis-cli --pipe 2>&1 | grep -E '^errors: 0,' >/dev/null || fail "写入出错：$*"; }
phase() { printf '%s\t%s\n' "$(X date +%s%3N | tr -d '\r')" "$1" >>"$OUT/phases.tsv"; }
info_field() { rc INFO "$1" | awk -F: -v k="$2" '$1 == k {print $2}'; }

"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null
CID=$("${C[@]}" ps -q redis)
JAVA=(docker run --rm --network "container:$CID" -v "$PWD:/w" -w /w "$JDK_IMAGE" java)
rc CONFIG GET '*' | paste - - | grep -E '^(latency-monitor-threshold|slowlog-log-slower-than|hz|active-expire-effort|lazyfree-lazy-expire|lazyfree-lazy-user-del|io-threads|client-output-buffer-limit|save|appendonly)\s' >"$OUT/config.tsv"

log "基线：容器内 intrinsic latency 5 秒"
X redis-cli --intrinsic-latency 5 2>&1 | tr '\r' '\n' | grep -E 'Max latency so far|avg latency' | tail -3 >"$OUT/intrinsic-latency.txt"

log "准备数据：100 万个 100 字节的 key（KEYS 与 fork 场景共用）"
pipe set key: 1000000 100
rc SLOWLOG RESET >/dev/null; rc LATENCY RESET >/dev/null; rc CONFIG RESETSTAT >/dev/null
echo -e "at_ms\tphase" >"$OUT/phases.tsv"
"${JAVA[@]}" src/Latency.java probe "$OUT/probe.tsv" >"$OUT/probe.log" 2>&1 & PROBE=$!
sleep 4
log "阶段：基线"
phase baseline; sleep 5
log "阶段：DEL 与 UNLINK 百万成员的 Set"
phase load; pipe sadd big:set 1000000; sleep 1
phase del; rc DEL big:set >/dev/null; sleep 3
phase load; pipe sadd big:set 1000000; sleep 1
phase unlink; rc UNLINK big:set >/dev/null; sleep 3
log "阶段：50 万个 key 集中过期（写入后 4 秒内全部到期），与 4—14 秒分散过期"
phase load; pipe set exp: 500000 32 4000; sleep 2
phase expiry_same; sleep 5
phase load; pipe set jit: 500000 32 4000 10000; sleep 2
phase expiry_jitter; sleep 13
log "阶段：长 Lua 与 KEYS"
phase lua; rc EVAL "local i = 0 while i < tonumber(ARGV[1]) do i = i + 1 end return i" 0 3000000 >/dev/null; sleep 2
phase keys; rc KEYS 'nomatch:*' >/dev/null; sleep 2
log "阶段：由 save 规则触发的 BGSAVE（fork）"
phase fork; rc CONFIG SET save "1 1" >/dev/null; rc SET trigger 1 >/dev/null
for i in $(seq 100); do [ "$(info_field persistence rdb_bgsave_in_progress)" = 0 ] && [ "$(info_field persistence rdb_last_bgsave_status)" = ok ] && [ "$(info_field persistence rdb_changes_since_last_save)" = 0 ] && break; sleep 0.2; done
rc CONFIG SET save "" >/dev/null; sleep 2
log "阶段：慢订阅者，发布 3 万条 4 KB 消息"
phase slow_subscriber
"${JAVA[@]}" src/Latency.java slowsub 30 >/dev/null 2>&1 & SUB=$!
sleep 3
# 在容器内每 0.2 秒采样一次，避免 docker exec 的开销拉长采样间隔
X sh -c 'echo "at_ms	connected_clients	pubsub_clients	mem_clients_normal	client_recent_max_output_buffer	client_output_buffer_limit_disconnections"
  for i in $(seq 60); do
    redis-cli INFO everything | tr -d "\r" | awk -F: -v t="$(date +%s%3N)" "{v[\$1] = \$2} END {printf \"%s\t%s\t%s\t%s\t%s\t%s\n\", t, v[\"connected_clients\"], v[\"pubsub_clients\"], v[\"mem_clients_normal\"], v[\"client_recent_max_output_buffer\"], v[\"client_output_buffer_limit_disconnections\"]}"
    sleep 0.2
  done' >"$OUT/slow-subscriber.tsv" & SAMPLER=$!
pipe publish news 30000 4096
wait $SAMPLER; kill "$SUB" 2>/dev/null || true; wait "$SUB" 2>/dev/null || true
phase end; sleep 1
touch "$OUT/probe.tsv.stop"; wait "$PROBE"

rc --json SLOWLOG GET 256 >"$OUT/slowlog.json"
rc --json LATENCY LATEST >"$OUT/latency-latest.json"
for e in $(python3 -c 'import json, sys; print(" ".join(x[0] for x in json.load(open(sys.argv[1]))))' "$OUT/latency-latest.json"); do echo "## $e"; rc --json LATENCY HISTORY "$e"; done >"$OUT/latency-history.txt"
rc LATENCY DOCTOR >"$OUT/latency-doctor.txt"
rc INFO commandstats >"$OUT/info-commandstats.txt"
rc INFO everything >"$OUT/info-end.txt"

log "pipeline：每批 1、10、100、1000 条 SET"
rc FLUSHALL SYNC >/dev/null
"${JAVA[@]}" src/Latency.java pipeline "$OUT/pipeline.tsv" >"$OUT/pipeline.log" 2>&1

{ "${C[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2"; echo "mem_limit: 2g"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "jdk_image: $JDK_IMAGE"
rm -f "$OUT"/*.stop  # 客户端的停止标记，没有内容
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
