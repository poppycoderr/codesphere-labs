#!/usr/bin/env bash
# 逃逸分析与标量替换：同一个方法在 8 种条件下每次调用实际分配的字节数（每种条件一个 JVM），JDK 21 与 JDK 25 各跑一遍
# 用法：scripts/verify.sh [输出目录]，默认 build/run；固定 digest 的 temurin 21、25 容器，约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"
J21="$JDK_IMAGE"
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
d() { docker run --rm --cpus 2 -v "$PWD:/w" -w /w "$@"; }
rm -rf build/classes && mkdir -p build/classes
d "$J21" javac -d build/classes src/Escape.java
f="$OUT/output.tsv"; : >"$f"
for v in 21 25; do
  img="J$v"; img="${!img}"
  run() { log "JDK $v：java $*"; d "$img" java "$@" | sed "s/^/jdk$v./" >>"$f"; }
  for s in local escape call bimorphic megamorphic; do run -cp build/classes Escape "$s"; done
  run -XX:-DoEscapeAnalysis -cp build/classes Escape local
  run -XX:CompileCommand=quiet -XX:CompileCommand=dontinline,Escape::consume -cp build/classes Escape call
  run -Xint -cp build/classes Escape local
done
{ d "$J21" java -version 2>&1 | head -2 | tail -1; d "$J25" java -version 2>&1 | head -2 | tail -1; } >"$OUT/jdk-versions.txt"
write_environment "$OUT/environment.txt" "jdk21_image: $J21" "jdk25_image: $J25" "container: 2 CPU"
median() { grep "^jdk$1\." "$f" | grep -F "$2" | sed -E 's/.*最后 10 批中位数 ([0-9.]+) 字节.*/\1/'; }
check() {
  for v in 21 25; do
    [ "$(median "$v" "$1")" = "$2" ] || fail "JDK $v $1：最后 10 批中位数应为 $2 字节，实际 $(median "$v" "$1")"
  done
  log "通过（JDK 21、25）：$1 → $2 字节/次"
}
check "local（默认参数）" "0.00"
check "call（默认参数）" "0.00"
check "bimorphic（默认参数）" "0.00"
check "escape（默认参数）" "24.00"
check "megamorphic（默认参数）" "24.00"
check "local（-XX:-DoEscapeAnalysis）" "24.00"
check "call（-XX:CompileCommand=quiet -XX:CompileCommand=dontinline,Escape::consume）" "24.00"
check "local（-Xint）" "24.00"
log "全部通过，输出在 $OUT"
