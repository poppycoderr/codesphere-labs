#!/usr/bin/env bash
# 普通字段做停止标记时，JIT 编译后工作线程看不到主线程的写入；-Xint 或 volatile 时能退出
# 三种方式各运行 3 次，约 40 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
f="$OUT/output.tsv"
: >"$f"
for i in 1 2 3; do
  printf 'default\tplain\t%s\t%s\n' "$i" "$(java src/StopFlag.java plain | tail -1)" >>"$f"
  printf 'Xint\tplain\t%s\t%s\n' "$i" "$(java -Xint src/StopFlag.java plain | tail -1)" >>"$f"
  printf 'default\tvolatile\t%s\t%s\n' "$i" "$(java src/StopFlag.java volatile | tail -1)" >>"$f"
done
write_environment "$OUT/environment.txt"
[ "$(grep -c $'^default\tplain\t.*alive after 3s: true$' "$f")" = 3 ] || fail "默认参数、普通字段应 3 次都停不下来"
log "通过：默认参数、普通字段，3 次都在 3 秒后仍然存活"
[ "$(grep -c $'^Xint\tplain\t.*alive after 3s: false$' "$f")" = 3 ] || fail "-Xint 应 3 次都退出"
log "通过：-Xint 解释执行，3 次都正常退出"
[ "$(grep -c $'^default\tvolatile\t.*alive after 3s: false$' "$f")" = 3 ] || fail "volatile 应 3 次都退出"
log "通过：volatile 字段，3 次都正常退出"
log "全部通过，输出在 $OUT"
