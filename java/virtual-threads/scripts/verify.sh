#!/usr/bin/env bash
# 虚拟线程的收益与上限（本机 JDK），以及 JDK 21 与 25 在 synchronized 内阻塞时的 pinning 差异（Docker，限制 6 核）
# 约 1 分钟；耗时只断言相对关系与理论值附近的范围
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
require docker
JDK21=eclipse-temurin@sha256:78ab9771b4650066c3ef748d46e05dbd6094d8bb34e0667a074486812efd655b
JDK25=eclipse-temurin@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2
java src/VT.java throughput >"$OUT/throughput.tsv"
java src/VT.java pinning >"$OUT/pinning-local.tsv"
docker run --rm --cpus 6 -v "$PWD/src:/src:ro" "$JDK21" sh -c 'java -version 2>&1 | head -1; java /src/VT.java pinning' >"$OUT/pinning-jdk21.tsv"
docker run --rm --cpus 6 -v "$PWD/src:/src:ro" "$JDK25" sh -c 'java -version 2>&1 | head -1; java /src/VT.java pinning' >"$OUT/pinning-jdk25.tsv"
write_environment "$OUT/environment.txt" "image_jdk21: $JDK21" "image_jdk25: $JDK25"
python3 - "$OUT" <<'PY'
import sys, pathlib
d = pathlib.Path(sys.argv[1])
def load(name):
    return {k: int(v) for k, v in (l.split("\t") for l in (d / name).read_text().splitlines() if "\t" in l)}
t = load("throughput.tsv")
assert t["platform-200-5000"] >= 1250 and t["virtual-5000"] < 250 and t["platform-200-5000"] > 5 * t["virtual-5000"], t
assert 2500 <= t["virtual-20conn-1000"] < 4000, t
print(f"通过：5000 个任务，平台线程池 {t['platform-200-5000']}ms，虚拟线程 {t['virtual-5000']}ms；20 个连接时 1000 个任务 {t['virtual-20conn-1000']}ms（理论 2500ms）")
for name, pinned in [("pinning-local.tsv", True), ("pinning-jdk21.tsv", True), ("pinning-jdk25.tsv", False)]:
    p = load(name)
    cpus = p["availableProcessors"]
    floor = 1000 // cpus * 50
    if pinned:
        assert p["synchronized-1000"] >= floor and p["reentrantlock-1000"] < 250, (name, p)
    else:
        assert p["synchronized-1000"] < 250 and p["reentrantlock-1000"] < 250, (name, p)
    print(f"通过：{name} {cpus} 核，synchronized {p['synchronized-1000']}ms，ReentrantLock {p['reentrantlock-1000']}ms"
          + (f"（被 pin 住，下限约 {floor}ms）" if pinned else "（不再 pin）"))
PY
log "全部通过，输出在 $OUT"
