#!/usr/bin/env bash
# 100 万行、10 列 xlsx 的读取内存：POI XSSFWorkbook 整体加载与 Apache Fesod 按批流式读取
# 需要约 8 GB 可用内存（POI 在 -Xmx8g 下运行一次）；约 4 分钟
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"
require_java 21
require mvn python3
mkdir -p "$OUT" build; find "$OUT" -mindepth 1 ! -name README.md -delete

mvn -B -q -Dstyle.color=never compile
mvn -B -q -Dstyle.color=never dependency:build-classpath -Dmdep.outputFile=target/cp.txt >/dev/null
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
CP="target/classes:$(cat target/cp.txt)"
run() { log "${*: -2:1} ${1}"; java "$@" 2>/dev/null | grep -E '^(generate|poi|fesod)' >>"$OUT/output.tsv"; }

[ -f build/orders.xlsx ] || run -cp "$CP" labs.excel.ExcelMemory generate build/orders.xlsx
if [ ! -s "$OUT/output.tsv" ]; then
  mb=$(python3 -c 'import os, sys; print(round(os.path.getsize(sys.argv[1]) / 1048576, 1))' build/orders.xlsx)
  printf 'generate\t1000000 行、10 列，文件 %s MB（沿用已生成的 build/orders.xlsx）\n' "$mb" >"$OUT/output.tsv"
fi
run -Xmx256m -cp "$CP" labs.excel.ExcelMemory fesod build/orders.xlsx
run -Xmx6g -cp "$CP" labs.excel.ExcelMemory poi build/orders.xlsx
run -Xmx8g -cp "$CP" labs.excel.ExcelMemory poi build/orders.xlsx
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"

python3 - "$OUT/output.tsv" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
fesod = re.search(r"^fesod\t-Xmx 约 (\d+) MB：读到数据行 1000000，堆使用峰值约 (\d+) MB", t, re.M)
assert fesod and int(fesod[1]) <= 256 and int(fesod[2]) < 256, t
assert re.search(r"^poi\t-Xmx 约 6144 MB：OutOfMemoryError", t, re.M), t
ok = re.search(r"^poi\t-Xmx 约 8192 MB：读到数据行 1000000，堆使用峰值约 (\d+) MB", t, re.M)
assert ok, t
print(f"通过：Fesod 在 256 MB 堆内读完 100 万行（峰值约 {fesod[2]} MB）；POI XSSFWorkbook 在 6 GB 堆下 OOM，8 GB 时读完（峰值约 {ok[1]} MB）")
PY
expect_line "$OUT/dependencies.txt" "org.apache.fesod:fesod-sheet:jar:2.0.2-incubating" "fesod-sheet 2.0.2-incubating"
expect_line "$OUT/dependencies.txt" "org.apache.poi:poi-ooxml:jar:5.5.1" "poi-ooxml 5.5.1"
log "全部通过，输出在 $OUT"
