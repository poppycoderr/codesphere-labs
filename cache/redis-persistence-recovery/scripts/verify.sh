#!/usr/bin/env bash
# Redis 持久化与恢复：进程崩溃与断电（LazyFS 丢弃未 fsync 的数据）下各配置丢多少已确认写入；
# 写延迟、multi-part AOF 的文件切换、fork 与写时复制、重启加载耗时、AOF 截断与损坏、BACKUP 与 preload-file 恢复
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个容器（2 CPU、3 GB），首次需要构建 LazyFS 镜像（约 4 分钟）；运行约 8 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
X() { "${C[@]}" exec -T redis "$@"; }
rc() { local p=$1; shift; X redis-cli -p "$p" "$@" | tr -d '\r'; }
now_ms() { X date +%s%3N | tr -d '\r'; }
# start_redis <端口> <目录> <日志> [redis-server 参数...]：后台启动，等到能响应 PING（加载完成）
start_redis() {
  local port=$1 dir=$2 logf=$3 i; shift 3
  X mkdir -p "$dir"
  X redis-server --port "$port" --dir "$dir" --daemonize yes --logfile "$logf" "$@" >/dev/null
  for i in $(seq 600); do [ "$(rc "$port" PING 2>/dev/null)" = PONG ] && return 0; sleep 0.1; done
  X tail -20 "$logf"; fail "端口 $port 的 Redis 没有启动"
}
stop_all() { X sh -c 'pkill -9 redis-server; sleep 0.3; true'; }
COUNT_LUA="local n = tonumber(ARGV[1]); local present, maxp = 0, 0
for i = 1, n do if redis.call('EXISTS', 'w:' .. i) == 1 then present = present + 1; maxp = i end end
local beyond, j = 0, n + 1
while redis.call('EXISTS', 'w:' .. j) == 1 do beyond = beyond + 1; j = j + 1 end
return {present, maxp, beyond, redis.call('DBSIZE')}"

"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --build --wait >/dev/null 2>&1
CID=$("${C[@]}" ps -q redis)
X redis-server --version >"$OUT/server.txt"
X sh -c 'grep " /data " /proc/mounts; cd /opt/lazyfs && git rev-parse HEAD' >"$OUT/lazyfs.txt"

cfg_args() {  # macOS 自带 bash 3.2 没有关联数组，用 case 映射配置名
  case $1 in
    none) echo "--save '' --appendonly no" ;;
    rdb-default) echo "--appendonly no" ;;
    aof-no) echo "--save '' --appendonly yes --appendfsync no" ;;
    aof-everysec) echo "--save '' --appendonly yes --appendfsync everysec" ;;
    aof-always) echo "--save '' --appendonly yes --appendfsync always" ;;
  esac
}
# crash_run <配置> <process|power> <轮次>：每秒 1000 条顺序写入，第 6 秒 SIGKILL redis-server。
# process 在普通文件系统（/plain）上进行，page cache 保留；power 在 LazyFS（/data）上进行，并丢弃未 fsync 的数据
crash_run() {
  local cfg=$1 kind=$2 run=$3 name="crash-$1-$2-$3" writer t_kill dir=/plain/crash
  [ "$kind" = power ] && dir=/data/crash
  stop_all; X sh -c "rm -rf $dir; mkdir -p $dir"
  eval "start_redis 6379 $dir /tmp/$name.log $(cfg_args $cfg)"
  X redis-cli CONFIG GET save appendonly appendfsync | tr -d '\r' | paste - - >"$OUT/$name-config.txt"
  rm -f "$OUT/$name-writer.tsv.stop"
  docker run --rm --network "container:$CID" -v "$PWD:/w" -w /w "$JDK_IMAGE" java src/Writer.java "$OUT/$name-writer.tsv" 1000 >"$OUT/$name-writer.log" 2>&1 & writer=$!
  sleep 6
  if [ "$kind" = power ]; then  # 写入期间不能经过 FUSE 读这个目录（会触发 LazyFS 的缓存错误），只读底层目录与 Redis 自己的统计
    { rc 6379 INFO persistence | grep -E '^(aof_current_size|aof_last_write_status|aof_delayed_fsync):'
      X sh -c 'echo "## 此刻已 fsync 到底层的文件（/lazyfs-root/crash）"; ls -ln /lazyfs-root/crash/appendonlydir 2>/dev/null; true'; } >"$OUT/$name-files-at-crash.txt"
  fi
  t_kill=$(now_ms); X pkill -9 redis-server || true
  if [ "$kind" = power ]; then  # 丢弃未 fsync 的数据，等到缓存视图与底层目录的文件大小一致
    X sh -c 'echo lazyfs::clear-cache > /tmp/faults.fifo
      v() { (cd "$1" 2>/dev/null && find . -type f -exec stat -c "%n %s" {} + | sort); }
      for i in $(seq 100); do [ "$(v /data/crash)" = "$(v /lazyfs-root/crash)" ] && exit 0; sleep 0.1; done; echo "clear-cache 未生效"; exit 1'
  fi
  wait "$writer" || true
  local t_start; t_start=$(now_ms)
  eval "start_redis 6379 $dir /tmp/$name-restart.log $(cfg_args $cfg)"
  local t_ready; t_ready=$(now_ms)
  local acked; acked=$(awk -F'\t' '$1 == "last_acked_seq" {print $2}' "$OUT/$name-writer.tsv")
  local res; res=$(rc 6379 EVAL "$COUNT_LUA" 0 "$acked" | paste -sd' ' -)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$cfg" "$kind" "$run" "$acked" "$(awk -F'\t' '$1 == "last_ack_at_ms" {print $2}' "$OUT/$name-writer.tsv")" "$t_kill" $((t_ready - t_start)) "$res" >>"$OUT/crash-matrix.tsv"
  X sh -c "cat /tmp/$name.log /tmp/$name-restart.log" >"$OUT/$name-redis.log"
}
log "场景一：崩溃矩阵。5 种配置的进程崩溃；none、rdb-default、aof-everysec（5 次）、aof-always（3 次）的断电"
echo -e "config\tcrash\trun\tlast_acked_seq\tlast_ack_at_ms\tkilled_at_ms\trestart_ready_ms\tpresent_of_acked max_present beyond_acked dbsize" >"$OUT/crash-matrix.tsv"
for cfg in none rdb-default aof-no aof-everysec aof-always; do crash_run "$cfg" process 1; done
for cfg in none rdb-default; do crash_run "$cfg" power 1; done
for run in 1 2 3 4 5; do crash_run aof-everysec power "$run"; done
for run in 1 2 3; do crash_run aof-always power "$run"; done
stop_all

log "场景二：写延迟（普通磁盘，redis-benchmark SET，64 字节值，1 个连接与 32 个连接）"
echo -e "config\tclients\trequests_per_second\tavg_ms\tp50_ms\tp99_ms\tmax_ms" >"$OUT/write-latency.tsv"
for cfg in none aof-no aof-everysec aof-always; do
  X rm -rf "/plain/bench-$cfg"
  eval "start_redis 6390 /plain/bench-$cfg /tmp/bench-$cfg.log $(cfg_args $cfg)"
  for clients in 1 32; do
    X redis-benchmark -p 6390 -t set -n 40000 -c "$clients" -r 100000 -d 64 >"$OUT/bench-$cfg-c$clients.txt" 2>&1
    python3 - "$OUT/bench-$cfg-c$clients.txt" "$cfg" "$clients" >>"$OUT/write-latency.tsv" <<'PY'
import re, sys
t = open(sys.argv[1]).read().replace("\r", "\n")
rps = re.findall(r"throughput summary: ([\d.]+) requests per second", t)[-1]
lat = re.search(r"latency summary \(msec\):\s*\n\s*avg\s+min\s+p50\s+p95\s+p99\s+max\s*\n\s*([\d.\s]+)", t).group(1).split()
print(f"{sys.argv[2]}\t{sys.argv[3]}\t{rps}\t{lat[0]}\t{lat[2]}\t{lat[4]}\t{lat[5]}")
PY
  done
  rc 6390 INFO persistence | grep -E '^aof_delayed_fsync:' | sed "s/^/$cfg /" >>"$OUT/write-latency-delayed-fsync.txt" || true
  rc 6390 SHUTDOWN NOSAVE >/dev/null 2>&1 || true
done

log "场景三：multi-part AOF。写入 20 万个 key，BGREWRITEAOF 期间继续写入，比较前后的目录与 manifest"
X rm -rf /plain/mp
start_redis 6391 /plain/mp /tmp/mp.log --save '' --appendonly yes --auto-aof-rewrite-percentage 0
python3 scripts/gen.py mp: 0 200000 64 | X redis-cli -p 6391 --pipe >/dev/null
{ echo "## 重写前"; X sh -c 'ls -ln /plain/mp/appendonlydir; cat /plain/mp/appendonlydir/appendonly.aof.manifest'; } >"$OUT/multipart-aof.txt"
rc 6391 BGREWRITEAOF >/dev/null
python3 scripts/gen.py mp2: 0 50000 64 | X redis-cli -p 6391 --pipe >/dev/null
for i in $(seq 100); do [ "$(rc 6391 INFO persistence | awk -F: '$1 == "aof_rewrite_in_progress" {print $2}')" = 0 ] && break; sleep 0.2; done
{ echo "## 重写后（重写期间又写入 5 万个 key）"; X sh -c 'ls -ln /plain/mp/appendonlydir; cat /plain/mp/appendonlydir/appendonly.aof.manifest'; echo "## DBSIZE"; X redis-cli -p 6391 DBSIZE; } >>"$OUT/multipart-aof.txt"
X grep -E 'AOF|rewrit|history|manifest' /tmp/mp.log | sed -E 's/^[0-9]+:[A-Z] [0-9]+ [A-Za-z]+ [0-9]+ [0-9:.]+ /<ts> /' >"$OUT/multipart-aof-log.txt"
rc 6391 SHUTDOWN NOSAVE >/dev/null 2>&1 || true

log "场景四：100 万个 key（约 100 字节值）的 fork、写时复制与四种文件的重启加载耗时"
X rm -rf /plain/load
start_redis 6392 /plain/load /tmp/load-1.log --save '' --appendonly yes --auto-aof-rewrite-percentage 0 --latency-monitor-threshold 1
python3 scripts/gen.py key: 0 1000000 100 | X redis-cli -p 6392 --pipe >/dev/null
rc 6392 INFO memory | grep -E '^used_memory:' >"$OUT/load-dataset.txt"; rc 6392 DBSIZE >>"$OUT/load-dataset.txt"
bgsave_wait() { local i; for i in $(seq 300); do [ "$(rc 6392 INFO persistence | awk -F: '$1 == "rdb_bgsave_in_progress" {print $2}')" = 0 ] && return; sleep 0.1; done; }
rc 6392 BGSAVE >/dev/null; sleep 0.2; bgsave_wait
rc 6392 INFO persistence | grep -E '^(rdb_last_bgsave_status|rdb_last_cow_size|rdb_last_bgsave_time_sec):' | sed 's/^/idle_/' >"$OUT/fork-cow.txt"
rc 6392 INFO stats | grep -E '^latest_fork_usec:' | sed 's/^/idle_/' >>"$OUT/fork-cow.txt"
X sh -c 'redis-benchmark -p 6392 -t set -r 1000000 -n 3000000 -d 100 -P 16 -c 4 -q >/dev/null 2>&1 &'; sleep 1
rc 6392 BGSAVE >/dev/null; sleep 0.2; bgsave_wait
X pkill -f 'redis-benchmark -p 6392' || true; sleep 0.5
rc 6392 INFO persistence | grep -E '^(rdb_last_bgsave_status|rdb_last_cow_size|rdb_last_bgsave_time_sec):' | sed 's/^/busy_/' >>"$OUT/fork-cow.txt"
rc 6392 INFO stats | grep -E '^latest_fork_usec:' | sed 's/^/busy_/' >>"$OUT/fork-cow.txt"
rc 6392 LATENCY HISTORY fork | paste -sd' ' - >"$OUT/fork-latency-history.txt"
X grep -E 'Fork CoW|BGSAVE done|Background saving' /tmp/load-1.log | sed -E 's/^[0-9]+:[A-Z] [0-9]+ [A-Za-z]+ [0-9]+ [0-9:.]+ /<ts> /' >"$OUT/fork-cow-log.txt"
# 以当前数据集为准重新写一份只有 incr 的 AOF：新实例从空开始，逐条写入
rc 6392 SHUTDOWN NOSAVE >/dev/null 2>&1 || true
X rm -rf /plain/load; start_redis 6392 /plain/load /tmp/load-2.log --save '' --appendonly yes --auto-aof-rewrite-percentage 0
python3 scripts/gen.py key: 0 1000000 100 | X redis-cli -p 6392 --pipe >/dev/null
restart_measure() {  # restart_measure <名称> [参数...]：干净关闭后重启，记录日志里的加载耗时与文件大小
  local name=$1; shift
  rc 6392 SHUTDOWN >/dev/null 2>&1 || true; sleep 0.5
  X sh -c 'cd /plain/load && find . -type f -exec ls -ln {} \;' >"$OUT/load-$name-files.txt"
  start_redis 6392 /plain/load "/tmp/load-$name.log" "$@"
  printf '%s\t%s\t%s\n' "$name" "$(rc 6392 DBSIZE)" "$(X grep -E 'DB loaded from|Done loading' "/tmp/load-$name.log" | tr '\n' ' ')" >>"$OUT/load-times.tsv"
}
echo -e "file\tdbsize\tlog" >"$OUT/load-times.tsv"
restart_measure aof-incr-only --save '' --appendonly yes --auto-aof-rewrite-percentage 0
rc 6392 CONFIG SET aof-use-rdb-preamble no >/dev/null; rc 6392 BGREWRITEAOF >/dev/null; sleep 1
for i in $(seq 100); do [ "$(rc 6392 INFO persistence | awk -F: '$1 == "aof_rewrite_in_progress" {print $2}')" = 0 ] && break; sleep 0.2; done
restart_measure aof-rewritten-plain --save '' --appendonly yes --auto-aof-rewrite-percentage 0 --aof-use-rdb-preamble no
rc 6392 CONFIG SET aof-use-rdb-preamble yes >/dev/null; rc 6392 BGREWRITEAOF >/dev/null; sleep 1
for i in $(seq 100); do [ "$(rc 6392 INFO persistence | awk -F: '$1 == "aof_rewrite_in_progress" {print $2}')" = 0 ] && break; sleep 0.2; done
restart_measure aof-rdb-preamble --save '' --appendonly yes --auto-aof-rewrite-percentage 0
rc 6392 SAVE >/dev/null
restart_measure rdb --save '' --appendonly no
rc 6392 SHUTDOWN NOSAVE >/dev/null 2>&1 || true

log "场景五：AOF 尾部截断、命令头损坏与值内部的静默损坏"
X rm -rf /plain/corrupt /plain/c1 /plain/c2 /plain/c3 /plain/c4
start_redis 6393 /plain/corrupt /tmp/corrupt.log --save '' --appendonly yes --auto-aof-rewrite-percentage 0
python3 scripts/gen.py c: 0 10000 32 | X redis-cli -p 6393 --pipe >/dev/null
rc 6393 SHUTDOWN >/dev/null 2>&1 || true; sleep 0.5
INCR=appendonlydir/appendonly.aof.1.incr.aof
# c1、c2：尾部截掉 5 字节；c3：第 5000 条命令（c:5000）的命令头写入 GARBAGE；c4：c:5000 的值内部写入 GARBAGE
X sh -c "for d in c1 c2 c3 c4; do cp -a /plain/corrupt /plain/\$d; done
  size=\$(stat -c %s /plain/c1/$INCR); truncate -s \$((size - 5)) /plain/c1/$INCR; truncate -s \$((size - 5)) /plain/c2/$INCR
  off=\$(grep -abo 'c:5000' /plain/c3/$INCR | head -1 | cut -d: -f1)
  printf GARBAGE | dd of=/plain/c3/$INCR bs=1 seek=\$((off - 17)) conv=notrunc 2>/dev/null
  printf GARBAGE | dd of=/plain/c4/$INCR bs=1 seek=\$((off + 13)) conv=notrunc 2>/dev/null
  echo incr_size=\$size key_offset=\$off" >"$OUT/corrupt-setup.txt"
strip_ts() { sed -E 's/^[0-9]+:[A-Z] [0-9]+ [A-Za-z]+ [0-9]+ [0-9:.]+ /<ts> /'; }
start_redis 6394 /plain/c1 /tmp/c1.log --save '' --appendonly yes
{ echo "dbsize=$(rc 6394 DBSIZE)"; X grep -E 'Warning|Truncat|AOF loaded|DB loaded' /tmp/c1.log | strip_ts || true; } >"$OUT/corrupt-truncated-default.txt"
rc 6394 SHUTDOWN NOSAVE >/dev/null 2>&1 || true
X redis-server --port 6395 --dir /plain/c2 --save '' --appendonly yes --aof-load-truncated no --logfile /tmp/c2.log --daemonize yes >/dev/null; sleep 2
{ echo "ping=$(rc 6395 PING 2>&1 | head -1)"; X grep -E 'Unexpected end|short read|aof-load-truncated|Bad file' /tmp/c2.log | strip_ts || true; } >"$OUT/corrupt-truncated-strict.txt"
X redis-server --port 6396 --dir /plain/c3 --save '' --appendonly yes --logfile /tmp/c3.log --daemonize yes >/dev/null; sleep 2
{ echo "ping=$(rc 6396 PING 2>&1 | head -1)"; X grep -E 'Bad file|Unrecoverable|redis-check-aof' /tmp/c3.log | strip_ts || true; } >"$OUT/corrupt-header-start.txt"
X sh -c 'cd /plain/c3/appendonlydir && redis-check-aof appendonly.aof.manifest 2>&1; echo "exit=$?"' >"$OUT/corrupt-header-check.txt" || true
X sh -c 'cd /plain/c3/appendonlydir && echo y | redis-check-aof --fix appendonly.aof.manifest 2>&1; echo "exit=$?"' >"$OUT/corrupt-header-fix.txt" || true
start_redis 6396 /plain/c3 /tmp/c3-fixed.log --save '' --appendonly yes
echo "dbsize_after_fix=$(rc 6396 DBSIZE)" >>"$OUT/corrupt-header-fix.txt"
start_redis 6397 /plain/c4 /tmp/c4.log --save '' --appendonly yes
{ echo "dbsize=$(rc 6397 DBSIZE)"; echo "c:4999=$(rc 6397 GET c:4999)"; echo "c:5000=$(rc 6397 GET c:5000)"; echo "c:5001=$(rc 6397 GET c:5001)"
  X sh -c 'cd /plain/c4/appendonlydir && redis-check-aof appendonly.aof.manifest 2>&1 | tail -3'; } >"$OUT/corrupt-value-silent.txt"
stop_all

log "场景六：BACKUP START / SEAL，之后误执行 FLUSHALL，用 preload-file 在新实例上恢复"
X rm -rf /plain/bk /plain/restore /plain/restore-src
start_redis 6397 /plain/bk /tmp/bk.log --save '' --appendonly yes
python3 scripts/gen.py a: 0 1000 16 | X redis-cli -p 6397 --pipe >/dev/null
{
  echo "## BACKUP START"; rc 6397 BACKUP START
  python3 scripts/gen.py b: 0 1000 16 | X redis-cli -p 6397 --pipe >/dev/null
  echo "## BACKUP LIST（SEAL 前）"; rc 6397 BACKUP LIST
  echo "## BACKUP SEAL"; rc 6397 BACKUP SEAL
  python3 scripts/gen.py c: 0 1000 16 | X redis-cli -p 6397 --pipe >/dev/null
  echo "## BACKUP LIST（SEAL 后）"; rc 6397 BACKUP LIST
  echo "## BACKUP STATUS"; rc 6397 BACKUP STATUS | paste - -
  echo "## 误操作"; rc 6397 FLUSHALL
  echo "## 原实例重启后的 DBSIZE"
} >"$OUT/backup.txt"
X sh -c 'mkdir -p /plain/restore-src && cp /plain/bk/backupdir/* /plain/restore-src/ && ls -ln /plain/restore-src && cat /plain/restore-src/appendonly.aof.manifest' >"$OUT/backup-files.txt"
rc 6397 SHUTDOWN >/dev/null 2>&1 || true; sleep 0.5
start_redis 6397 /plain/bk /tmp/bk-restart.log --save '' --appendonly yes
rc 6397 DBSIZE >>"$OUT/backup.txt"
start_redis 6398 /plain/restore /tmp/restore.log --save '' --appendonly yes --preload-file aof:/plain/restore-src/appendonly.aof.manifest
count() { rc 6398 EVAL "return #redis.call('KEYS', ARGV[1])" 0 "$1"; }
{ echo -e "prefix\tkeys"; for p in a b c; do printf '%s\t%s\n' "$p" "$(count "$p:*")"; done; echo -e "sample_b999\t$(rc 6398 GET b:999)"; } >"$OUT/backup-restored.tsv"
X grep -iE 'preload|loaded|BACKUP' /tmp/restore.log /tmp/bk.log | sed -E 's/^([^:]+):[0-9]+:[A-Z] [0-9]+ [A-Za-z]+ [0-9]+ [0-9:.]+ /\1 <ts> /' >"$OUT/backup-log.txt"
stop_all

{ "${C[@]}" config --images | sed 's/^/image: /'; echo "base: redis:8.10.1（docker/Dockerfile 固定 digest）+ LazyFS（固定 commit，见 lazyfs.txt）"; echo "cpus: 2"; echo "mem_limit: 3g"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1" "jdk_image: $JDK_IMAGE"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
