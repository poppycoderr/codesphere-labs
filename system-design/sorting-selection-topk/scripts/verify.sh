#!/usr/bin/env bash
# 排序、选择与 Top K：减法比较器溢出、同分规则、三种做法在不同 K 下的耗时、分片 Top K 的准确性
# 约 1 分钟；耗时只断言相对关系
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java -Xmx2g src/TopK.java >"$OUT/output.tsv"
write_environment "$OUT/environment.txt"
f="$OUT/output.tsv"
expect_line "$f" "(int) (a.createdAt - b.createdAt)：抛出 IllegalArgumentException：Comparison method violates its general contract!" "减法比较器溢出：大列表排序抛出契约异常"
expect_regex "$f" "overflow\.small	同样的比较器只排 20 条：没有异常，相邻逆序 [1-9][0-9]* 处" "小列表不抛异常，但顺序是错的"
expect_line "$f" "改用 Comparator.comparingLong：相邻逆序 0 处" "comparingLong 排序正确"
expect_regex "$f" "ties\.score	.*是否相同=false" "只按分数比较：三种做法选出的前 100 条不一致"
expect_regex "$f" "ties\.full	.*是否相同=true" "加上提交时间与 ID 作为同分规则：三者一致"
expect_line "$f" "3 个分片各取前 100 再合并：与全局前 100 相同=true" "按记录排名：分片取 Top K 再合并是准确的"
expect_line "$f" "每个分片上报本地前 2：合并后第一名 a=50（全局真实第一名 x=120）" "按 key 聚合：本地前 2 漏掉全局第一"
expect_line "$f" "每个分片上报本地前 3：合并后第一名 x=120" "每个分片多报一些才找回全局第一"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
c = {int(k): tuple(map(int, v)) for k, *v in re.findall(r"^cost\.(\d+)\t.*全排序 (\d+) ms，堆 (\d+) ms，快速选择 (\d+) ms", t, re.M)}
s, h, q = c[100]
assert h * 5 < s and q * 2 < s, c
assert c[500000][1] > 5 * h and c[500000][2] < c[500000][1], c
print(f"通过：K=100 时堆 {h} ms、快速选择 {q} ms，远快于全排序 {s} ms；K=500,000 时堆 {c[500000][1]} ms，慢于快速选择 {c[500000][2]} ms")
PY
log "全部通过，输出在 $OUT"
