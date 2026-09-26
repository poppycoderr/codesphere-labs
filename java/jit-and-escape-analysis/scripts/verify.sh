#!/usr/bin/env bash
# 逃逸分析与标量替换：同一个方法在 8 种条件下每次调用实际分配的字节数（每种条件一个 JVM）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
rm -rf build/classes && mkdir -p build/classes && javac -d build/classes src/Escape.java
f="$OUT/output.tsv"; : >"$f"
run() { log "java $*"; java "$@" >>"$f"; }
for s in local escape call bimorphic megamorphic; do run -cp build/classes Escape "$s"; done
run -XX:-DoEscapeAnalysis -cp build/classes Escape local
run -XX:CompileCommand=quiet -XX:CompileCommand=dontinline,Escape::consume -cp build/classes Escape call
run -Xint -cp build/classes Escape local
write_environment "$OUT/environment.txt"
median() { grep -F "$1" "$f" | sed -E 's/.*最后 10 批中位数 ([0-9.]+) 字节.*/\1/'; }
check() { [ "$(median "$1")" = "$2" ] || fail "$1：最后 10 批中位数应为 $2 字节，实际 $(median "$1")"; log "通过：$1 → $2 字节/次"; }
check "local（默认参数）" "0.00"
check "call（默认参数）" "0.00"
check "bimorphic（默认参数）" "0.00"
check "escape（默认参数）" "24.00"
check "megamorphic（默认参数）" "24.00"
check "local（-XX:-DoEscapeAnalysis）" "24.00"
check "call（-XX:CompileCommand=quiet -XX:CompileCommand=dontinline,Escape::consume）" "24.00"
check "local（-Xint）" "24.00"
log "全部通过，输出在 $OUT"
