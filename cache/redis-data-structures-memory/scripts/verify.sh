#!/usr/bin/env bash
# Redis 数据结构与编码：编码阈值、跨阈值的内存代价、Hash 分桶、计数结构、DEL 与 UNLINK
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Redis 容器，2 CPU、1 GB 内存；约 2 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
rc() { "${C[@]}" exec -T redis redis-cli "$@"; }
load() {  # load <gen.py 参数...>：用 --pipe 写入，出现错误回复即失败
  local r; r=$(python3 scripts/gen.py "$@" | "${C[@]}" exec -T redis redis-cli --pipe 2>&1 | tail -1)
  [[ "$r" == *"errors: 0,"* ]] || fail "写入出错：$r"
}
mem() { rc INFO memory | tr -d '\r' | awk -F: -v k="$1" '$1 == k {print $2}'; }
fresh() { rc FLUSHALL SYNC >/dev/null; rc MEMORY PURGE >/dev/null; sleep 0.5; }
enc() { printf '%s\t%s\t%s\t%s\n' "$1" "$(rc OBJECT ENCODING "$1" | tr -d '\r')" "$(rc MEMORY USAGE "$1" SAMPLES 0 | tr -d '\r')" "$2"; }

"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null
rc INFO server | tr -d '\r' | grep -E '^(redis_version|redis_mode|os|arch_bits|mem_allocator|io_threads_active):' >"$OUT/server.txt"
for p in hash-max-listpack-entries hash-max-listpack-value zset-max-listpack-entries zset-max-listpack-value \
         set-max-intset-entries set-max-listpack-entries set-max-listpack-value list-max-listpack-size \
         lazyfree-lazy-user-del lazyfree-lazy-user-flush maxmemory save appendonly; do
  printf '%s\t%s\n' "$p" "$(rc CONFIG GET "$p" | tr -d '\r' | sed -n 2p)"
done >"$OUT/config.tsv"

log "场景一：各结构在阈值两侧的编码与 MEMORY USAGE"
fresh
{
  echo -e "key\tencoding\tmemory_usage_bytes\tdescription"
  load hash h:512 512; enc h:512 "Hash 512 个字段，值 8 字节"
  load hash h:513 513; enc h:513 "Hash 513 个字段"
  load hash h:v64 10 64; enc h:v64 "Hash 10 个字段，值 64 字节"
  load hash h:v65 10 65; enc h:v65 "Hash 10 个字段，值 65 字节"
  load zset z:128 128; enc z:128 "ZSet 128 个成员"
  load zset z:129 129; enc z:129 "ZSet 129 个成员"
  load set s:int512 512; enc s:int512 "Set 512 个整数"
  load set s:int513 513; enc s:int513 "Set 513 个整数"
  load set s:str128 128 str; enc s:str128 "Set 128 个字符串"
  load set s:str129 129 str; enc s:str129 "Set 129 个字符串"
  load list l:small 100 8; enc l:small "List 100 个 8 字节元素"
  load list l:big 2000 8; enc l:big "List 2000 个 8 字节元素"
  rc SET str:int 12345 >/dev/null; enc str:int "String 12345"
  rc SET str:100 "$(printf '%100s' | tr ' ' a)" >/dev/null; enc str:100 "String 100 字节"
} >"$OUT/encodings.tsv"
# Redis 8 把 key 与短字符串值放进同一次内存分配，embstr 的上限随 key 长度变化；逐个长度找出分界
{
  echo -e "key\tkey_bytes\tvalue_bytes\tencoding\tmemory_usage_bytes"
  for k in k user:1001 user:session:1234567890123; do
    for n in $(seq 1 48); do
      rc SET "$k" "$(printf "%${n}s" | tr ' ' a)" >/dev/null
      printf '%s\t%s\t%s\t%s\t%s\n' "$k" "${#k}" "$n" "$(rc OBJECT ENCODING "$k" | tr -d '\r')" "$(rc MEMORY USAGE "$k" SAMPLES 0 | tr -d '\r')"
    done
  done
} >"$OUT/embstr-boundary.tsv"

log "场景二：Hash 从 500 到 600 个字段，再删回 500 个"
fresh
{
  echo -e "key\tencoding\tmemory_usage_bytes\tdescription"
  load hash h:500 500; enc h:500 "500 个字段"
  load hash h:600 600; enc h:600 "600 个字段"
  python3 -c 'print("HDEL h:600 " + " ".join(f"f{i}" for i in range(500, 600)))' | "${C[@]}" exec -T redis redis-cli >/dev/null
  enc h:600 "600 个字段删掉 100 个，剩 $(rc HLEN h:600 | tr -d '\r') 个"
} >"$OUT/hash-crossing.tsv"

log "场景三：10 万条小对象，独立 String key 与 Hash 分桶"
{
  echo -e "layout\tkeys\tused_memory_before\tused_memory_after\tdelta_bytes\tsample_encoding"
  for layout in strings buckets:500 buckets:1000; do
    fresh; before=$(mem used_memory)
    case $layout in
      strings) load strings 100000; sample=$(rc OBJECT ENCODING user:0 | tr -d '\r') ;;
      buckets:*) load buckets 100000 "${layout#buckets:}"; sample=$(rc OBJECT ENCODING user:h:0 | tr -d '\r') ;;
    esac
    after=$(mem used_memory)
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$layout" "$(rc DBSIZE | tr -d '\r')" "$before" "$after" $((after - before)) "$sample"
  done
} >"$OUT/buckets.tsv"

log "场景四：一百万个连续用户 ID，Set、Bitmap、HyperLogLog；稀疏偏移量的 Bitmap"
fresh
load set uv:set 1000000
load bitmap uv:bitmap 1000000
load hll uv:hll 1000000
rc SETBIT uv:sparse 100000000 1 >/dev/null
{
  echo -e "key\tencoding\tmemory_usage_bytes\tcount"
  printf 'uv:set\t%s\t%s\t%s\n' "$(rc OBJECT ENCODING uv:set | tr -d '\r')" "$(rc MEMORY USAGE uv:set | tr -d '\r')" "$(rc SCARD uv:set | tr -d '\r')"
  printf 'uv:bitmap\t%s\t%s\t%s\n' "$(rc OBJECT ENCODING uv:bitmap | tr -d '\r')" "$(rc MEMORY USAGE uv:bitmap | tr -d '\r')" "$(rc BITCOUNT uv:bitmap | tr -d '\r')"
  printf 'uv:hll\t%s\t%s\t%s\n' "$(rc OBJECT ENCODING uv:hll | tr -d '\r')" "$(rc MEMORY USAGE uv:hll | tr -d '\r')" "$(rc PFCOUNT uv:hll | tr -d '\r')"
  printf 'uv:sparse\t%s\t%s\t%s\n' "$(rc OBJECT ENCODING uv:sparse | tr -d '\r')" "$(rc MEMORY USAGE uv:sparse | tr -d '\r')" "$(rc BITCOUNT uv:sparse | tr -d '\r')"
} >"$OUT/counting.tsv"
rc SMISMEMBER uv:set 1 999999 1000001 | tr -d '\r' >"$OUT/counting-membership.txt"

log "场景五：删除百万元素的 Set：DEL、UNLINK、打开 lazyfree-lazy-user-del 后的 DEL，各 5 次（SLOWLOG 记录的服务端执行耗时）"
rc CONFIG SET slowlog-log-slower-than 0 >/dev/null
{
  echo -e "command\tlazyfree_lazy_user_del\trun\tmembers\tserver_micros\tlazyfree_pending_after"
  for variant in DEL UNLINK DEL-lazy; do
    if [ "$variant" = DEL-lazy ]; then rc CONFIG SET lazyfree-lazy-user-del yes >/dev/null; else rc CONFIG SET lazyfree-lazy-user-del no >/dev/null; fi
    for run in 1 2 3 4 5; do
      fresh; load set big:set 1000000
      members=$(rc SCARD big:set | tr -d '\r')
      rc SLOWLOG RESET >/dev/null
      rc "${variant%-lazy}" big:set >/dev/null
      micros=$(rc SLOWLOG GET 10 | tr -d '\r' | python3 -c '
import sys
lines = sys.stdin.read().split("\n")
# SLOWLOG GET 的纯文本输出：每条依次为 id、时间戳、耗时（微秒）、参数……；取命令名前一行
for i, l in enumerate(lines):
    if l.upper() in ("DEL", "UNLINK") and i >= 3:
        print(lines[i - 1]); break
' )
      pending=$(rc INFO memory | tr -d '\r' | awk -F: '$1 == "lazyfree_pending_objects" {print $2}')
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "${variant%-lazy}" "$(rc CONFIG GET lazyfree-lazy-user-del | tr -d '\r' | sed -n 2p)" "$run" "$members" "$micros" "$pending"
    done
  done
} >"$OUT/delete.tsv"
rc CONFIG SET lazyfree-lazy-user-del no >/dev/null
rc SLOWLOG GET 3 | tr -d '\r' >"$OUT/delete-slowlog-sample.txt"

{ "${C[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2"; echo "mem_limit: 1g"; echo "command: redis-server --save '' --appendonly no"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
