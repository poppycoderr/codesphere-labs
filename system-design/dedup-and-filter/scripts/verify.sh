#!/usr/bin/env bash
# 判存结构与敏感词过滤：位图与 HashSet 的内存、布隆过滤器的误判与漏判、前缀树与逐词 indexOf
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：宿主机 JDK 21，堆 4 GB；约 2 分钟（首次需要下载 JOL）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
JOL=$(maven_jar org.openjdk.jol:jol-core:0.17)
java -Xmx4g -Djdk.attach.allowAttachSelf=true -cp "$JOL" src/DedupFilter.java 2>/dev/null | grep -v "^#" >"$OUT/output.tsv"
write_environment "$OUT/environment.txt" "jol-core: 0.17" "heap: -Xmx4g"
f="$OUT/output.tsv"
cat "$f" >&2
expect_line "$f" "40 亿个号码：位图 0.47 GiB，long[] 存原始值 29.8 GiB" "40 亿个号码的位图与 long[] 的大小"
expect_regex "$f" "^bloom\.0\.01	.*位数组 9,585,059 bit（1,170 KB），哈希 7 个；.*已加入的判为不存在 0 个$" "1% 误判率：位数与哈希个数符合公式，没有漏判"
expect_regex "$f" "^bloom\.0\.001	.*位数组 14,377,588 bit（1,755 KB），哈希 10 个；.*已加入的判为不存在 0 个$" "0.1% 误判率：位数与哈希个数符合公式，没有漏判"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
ratio = int(re.search(r"KB，(\d+) 倍；cardinality=10,000,000", t)[1])
assert ratio > 100, ratio
for p, rate in re.findall(r"目标误判率 ([\d.]+)：.*?（([\d.]+)%）", t):
    assert abs(float(rate) / 100 / float(p) - 1) < 0.15, (p, rate)
m = re.search(r"前缀树 ([\d.]+) ms、命中 (\d+) 次；逐词 indexOf ([\d.]+) ms、命中 (\d+) 次", t)
tm, th, nm, nh = float(m[1]), int(m[2]), float(m[3]), int(m[4])
assert th == nh and nm > 50 * tm, (tm, th, nm, nh)
print(f"通过：位图省 {ratio} 倍；实测误判率与目标相差不到 15%；前缀树与逐词命中都是 {th} 次，快 {nm / tm:.0f} 倍")
PY
log "全部通过，输出在 $OUT"
