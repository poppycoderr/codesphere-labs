#!/usr/bin/env bash
# 收集器对比：同一负载下 G1、分代 ZGC、Parallel 的吞吐、CPU、停顿与探针延迟；堆余量不足时 G1 的 Full GC 与 ZGC 的分配停顿；System.gc() 的四种行为
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：JDK 25 容器（4 CPU、3 GB）；约 5 分钟（不含拉取镜像）；SECONDS_RUN 可缩短每轮测量时间，仅用于调试
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
mkdir -p build/tmp
SECONDS_RUN="${SECONDS_RUN:-20}"
JDK25_IMAGE="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
jdk() { docker run --rm --cpus 4 --memory 3g -v "$PWD:/w" -w /w "$JDK25_IMAGE" java "$@"; }
LOGOPT="gc,gc+phases=info:file=/w/build/tmp/gc.log:uptime,level,tags"
run() {
  local name="$1" live="$2" mode="$3"; shift 3
  log "负载 $name：长期存活 ${live} MB、${mode} 替换，测量 ${SECONDS_RUN} 秒"
  jdk -Xms2g -Xmx2g "$@" -Xlog:"$LOGOPT" src/Workload.java "$live" "$SECONDS_RUN" "$mode" >"$OUT/run-$name.txt"
  normalize_paths <build/tmp/gc.log >"$OUT/gc-$name.log"
}
for round in 1 2; do
  run "g1-$round" 800 fifo -XX:+UseG1GC
  run "zgc-$round" 800 fifo -XX:+UseZGC
  run "parallel-$round" 800 fifo -XX:+UseParallelGC
done
run g1-random 800 random -XX:+UseG1GC
run zgc-random 800 random -XX:+UseZGC
run g1-live1700 1700 fifo -XX:+UseG1GC
run zgc-live1700 1700 fifo -XX:+UseZGC
explicit() {
  local name="$1"; shift
  log "System.gc()：$name"
  jdk -Xms2g -Xmx2g "$@" -Xlog:"$LOGOPT" src/ExplicitGc.java >"$OUT/explicit-$name.txt"
  normalize_paths <build/tmp/gc.log >"$OUT/gc-explicit-$name.log"
}
explicit g1 -XX:+UseG1GC
explicit g1-concurrent -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent
explicit g1-disabled -XX:+UseG1GC -XX:+DisableExplicitGC
explicit zgc -XX:+UseZGC
python3 scripts/summarize.py "$OUT" >/dev/null
docker run --rm "$JDK25_IMAGE" java -version 2>&1 | head -1 >"$OUT/java-version.txt"
write_environment "$OUT/environment.txt" "jdk_image: $JDK25_IMAGE" "container: 4 CPU、3 GB；堆固定 2 GB"
cat "$OUT/summary.tsv" >&2
python3 - "$OUT/summary.tsv" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
def run(name):
    m = re.search(rf"^run\.{re.escape(name)}\t吞吐 (\d+)k.*?CPU ([\d.]+) 核，停顿 (\d+) 次，合计 (\d+) ms，最长 ([\d.]+) ms，Full GC (\d+) 次，探针 p99 ([\d.]+) ms、p99.9 ([\d.]+) ms.*?分配停顿 (\d+) 次", t, re.M)
    assert m, name
    ops, cpu, n, tot, mx, full, p99, p999, stalls = m.groups()
    return dict(ops=int(ops), cpu=float(cpu), n=int(n), total=int(tot), max=float(mx), full=int(full), p99=float(p99), p999=float(p999), stalls=int(stalls))
for r in (1, 2):
    g, z, p = run(f"g1-{r}"), run(f"zgc-{r}"), run(f"parallel-{r}")
    assert z["max"] < 2 and z["max"] * 5 < g["max"] and z["max"] * 5 < p["max"], (g, z, p)   # 最长停顿：ZGC 低一个数量级以上
    assert z["total"] * 10 < g["total"] and z["p999"] < g["p999"], (g, z)                  # 停顿合计与探针尾延迟：ZGC 更低
    assert g["full"] == 0 and p["full"] > 0, (g, p)                                        # 顺序替换：G1 不退化，Parallel 周期性 Full GC
gr, zr, z14 = run("g1-random"), run("zgc-random"), run("zgc-live1700")
assert gr["full"] > 0, gr                                                                  # 随机替换：G1 退化出 Full GC
assert zr["full"] == 0 and zr["stalls"] == 0 and zr["max"] < 2, zr                          # ZGC 不受替换方式影响
assert z14["stalls"] > 0 and z14["max"] < 2, z14                                            # 堆余量不足：停顿仍短，但出现分配停顿
assert re.search(r"^explicit\.g1\t日志：Pause Full \(System\.gc\(\)\)", t, re.M)
assert re.search(r"^explicit\.g1-concurrent\t日志：Pause Young \(Concurrent Start\) \(System\.gc\(\)\)", t, re.M)
assert re.search(r"^explicit\.g1-disabled\t日志：无；调用期间停顿 0 次", t, re.M)
assert re.search(r"^explicit\.zgc\t日志：Major Collection \(System\.gc\(\)\)", t, re.M)
print("通过：两轮中 ZGC 最长停顿低一个数量级以上、停顿合计与探针 p99.9 更低；顺序替换时 G1 无 Full GC、Parallel 周期性 Full GC；随机替换时 G1 退化出 Full GC、ZGC 不受影响；存活 1,700 MB 时 ZGC 出现分配停顿；System.gc() 四种行为与日志一致")
PY
log "全部通过，输出在 $OUT"
