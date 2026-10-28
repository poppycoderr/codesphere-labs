#!/usr/bin/env bash
# GC 基线：容器内的默认堆参数、堆大小与暂停目标对 G1 的影响（JDK 21 与 25 对照）、jstat 与 jcmd 的输出、OOM 自动转储的文件大小
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 21、25 容器（2 CPU、4 GB）；约 4 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
J21="$JDK_IMAGE"
JDK_IMAGE="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
mkdir -p build/tmp
jdk() { docker run --rm --cpus 2 --memory "$1" -v "$PWD:/w" -w /w "${IMG:-$JDK_IMAGE}" "${@:2}"; }

log "默认值：容器内存 2 GB 与 4 GB"
for mem in 2g 4g; do
  jdk "$mem" java -XX:+PrintFlagsFinal -version 2>/dev/null \
    | grep -E ' (UseG1GC|MaxGCPauseMillis|G1HeapRegionSize|InitialHeapSize|MaxHeapSize|UseContainerSupport|MaxRAMPercentage) ' \
    | awk '{print $2" = "$4}' >"$OUT/flags-$mem.txt"
done
jdk 2g java -XX:MaxRAMPercentage=70.0 -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E ' MaxHeapSize ' | awk '{print $2" = "$4}' >"$OUT/flags-2g-ram70.txt"

log "堆大小与暂停目标：同一负载各跑 8 秒，每种 3 次，JDK 21 与 JDK 25 各一组"
run_gc() {
  local name="$1"; shift
  for rep in 1 2 3; do
    jdk 4g java "$@" -Xlog:gc:file="/w/build/tmp/gc-$name-$rep.log":uptime src/Alloc.java 8 >"$OUT/alloc-$name-$rep.txt"
    normalize_paths <"build/tmp/gc-$name-$rep.log" >"$OUT/gc-$name-$rep.log"
  done
}
for v in 21 25; do
  if [ "$v" = 21 ]; then IMG="$J21"; else IMG="$JDK_IMAGE"; fi
  run_gc "jdk$v-xmx512m" -Xms512m -Xmx512m
  run_gc "jdk$v-xmx2g" -Xms2g -Xmx2g
  run_gc "jdk$v-xmx512m-pause50" -Xms512m -Xmx512m -XX:MaxGCPauseMillis=50
done
unset IMG
python3 scripts/summarize.py "$OUT"

log "运行中的进程：jcmd GC.heap_info 与 jstat -gcutil"
docker rm -f csl-gc-baseline >/dev/null 2>&1 || true
docker run -d --name csl-gc-baseline --cpus 2 --memory 4g -v "$PWD:/w" -w /w "$JDK_IMAGE" \
  bash -c 'java -Xms512m -Xmx512m src/Alloc.java 20' >/dev/null
sleep 8
PID=$(docker exec csl-gc-baseline jcmd -l | awk '/Alloc/ {print $1; exit}')
docker exec csl-gc-baseline jcmd "$PID" GC.heap_info >"$OUT/heap-info.txt"
docker exec csl-gc-baseline jstat -gcutil "$PID" 1000 3 >"$OUT/jstat.txt"
docker rm -f csl-gc-baseline >/dev/null

log "OOM 自动转储：-Xmx256m，分别保留 64 KB 与 1 MB 的数组"
for kb in 64 1024; do
  rm -f build/tmp/*.hprof
  jdk 2g java -Xmx256m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/w/build/tmp/oom.hprof src/Oom.java "$kb" 2>&1 | normalize_paths >"$OUT/oom-${kb}k.log" || true
  bytes=$(wc -c <build/tmp/oom.hprof | tr -d ' ')
  echo "oom.dump.${kb}k	-Xmx256m、每次保留 ${kb} KB 的数组，OutOfMemoryError 后生成的 hprof：$((bytes / 1048576)) MB" >>"$OUT/summary.tsv"
done
rm -f build/tmp/oom.hprof
{ docker run --rm "$J21" java -version 2>&1 | sed -n 2p; docker run --rm "$JDK_IMAGE" java -version 2>&1 | sed -n 2p; } >"$OUT/jdk-versions.txt"
write_environment "$OUT/environment.txt" "jdk21_image: $J21" "jdk25_image: ${JDK_IMAGE}（默认参数、jcmd、jstat、OOM 只在 JDK 25 上跑）" "container: 2 CPU，内存 2 GB 或 4 GB"
cat "$OUT"/flags-*.txt "$OUT/summary.tsv" >&2

f="$OUT/summary.tsv"
expect_line "$OUT/flags-2g.txt" "MaxHeapSize = 536870912" "容器内存 2 GB：最大堆为 1/4，即 512 MB"
expect_line "$OUT/flags-2g.txt" "InitialHeapSize = 33554432" "容器内存 2 GB：初始堆为 1/64，即 32 MB"
expect_line "$OUT/flags-4g.txt" "MaxHeapSize = 1073741824" "容器内存 4 GB：最大堆 1 GB"
expect_line "$OUT/flags-2g.txt" "MaxGCPauseMillis = 200" "G1 默认暂停目标 200 ms"
expect_regex "$OUT/flags-2g-ram70.txt" "MaxHeapSize = 150[0-9]{7}" "MaxRAMPercentage=70：最大堆约为容器内存的 70%"
expect_regex "$OUT/oom-64k.log" "java.lang.OutOfMemoryError: Java heap space" "OOM 发生"
expect_regex "$OUT/oom-64k.log" "Dumping heap to /w/build/tmp/oom.hprof" "OOM 时自动转储"
expect_regex "$OUT/jstat.txt" "YGC +YGCT +FGC +FGCT +CGC +CGCT +GCT" "jstat 输出列"
expect_regex "$OUT/heap-info.txt" "garbage-first heap +total reserved 524288K" "jcmd GC.heap_info：512 MB 的 G1 堆"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
g = {k: (int(n), float(avg), int(full)) for k, n, avg, full in re.findall(r"^gc\.(\S+)\t.*Young GC (\d+) 次，平均 ([\d.]+) ms.*?Full GC (\d+) 次", t, re.M)}
for v in ("jdk21", "jdk25"):
    a, b = g[f"{v}-xmx512m"], g[f"{v}-xmx2g"]
    assert b[0] * 5 < a[0] and b[2] == 0, (v, a, b)   # 堆变大：Young GC 次数少得多，2 GB 堆没有 Full GC
dump = int(re.search(r"64 KB 的数组.*hprof：(\d+) MB", t)[1])
big = int(re.search(r"1024 KB 的数组.*hprof：(\d+) MB", t)[1])
assert 200 <= dump <= 300 and big < dump * 0.7, (dump, big)
print("通过：" + "；".join(f"{k} Young GC {n} 次、平均 {avg} ms、Full GC {full} 次" for k, (n, avg, full) in g.items()) + f"；OOM 转储 {dump} MB / {big} MB")
PY
log "全部通过，输出在 $OUT"
