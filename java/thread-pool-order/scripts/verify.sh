#!/usr/bin/env bash
# ThreadPoolExecutor 的接收顺序：core=2、max=4、有界队列 2，提交 7 个卡住的任务，输出是确定的
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java src/PoolOrder.java >"$OUT/output.txt"
write_environment "$OUT/environment.txt"
diff -u expected.txt "$OUT/output.txt" || fail "输出与 expected.txt 不一致"
log "通过：第 3、4 个任务排队，第 5、6 个任务才扩到 4 个线程，第 7 个被拒绝"
log "全部通过，输出在 $OUT"
