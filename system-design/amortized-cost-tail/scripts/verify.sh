#!/usr/bin/env bash
# 均摊 O(1) 的单次尖峰（ArrayList 扩容、HashMap rehash）与小 N 下顺序扫描、哈希查找的交叉点
# 约 1 分钟；耗时只断言相对关系
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
f="$OUT/output.tsv"
# 扩容场景排除 GC 与缺页：Epsilon GC 不回收，预触整个堆
isolated=(-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms6g -Xmx6g -XX:+AlwaysPreTouch)
{
  java "${isolated[@]}" src/Amortized.java list
  java "${isolated[@]}" src/Amortized.java map
  java -Xmx2g src/Amortized.java lookup
  java -Xmx2g src/Amortized.java lookup-skewed
  java -Xmx2g src/Amortized.java lookup-int
} >"$f"
write_environment "$OUT/environment.txt"
expect_line "$f" "默认容量加入 10,000,000 个元素：扩容 36 次，累计复制 27,690,301 个引用（2.77 倍 N）" "ArrayList 扩容次数与累计复制量"
expect_line "$f" "new HashMap<>(2,000,000)：table 2,097,152，阈值 1,572,864，放入第 1,572,865 个键时仍要 rehash 一次" "按容量构造的 HashMap 的 rehash 点"
expect_line "$f" "默认容量放入 2,000,000 个键：rehash 18 次，累计搬移 3,145,734 个节点（1.57 倍 N）" "HashMap rehash 次数与累计搬移量"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
def get(key, pat):
    m = re.search(rf"^{re.escape(key)}\t.*{pat}", t, re.M)
    assert m, key
    return m.groups()
for c in ("list", "map"):
    d_mean, d_max, d_slow = map(float, get(f"{c}.default", r"均值 ([\d.]+) ns/次.*最大 ([\d.]+) ms，≥100µs 的操作 (\d+) 次"))
    p_max, = map(float, get(f"{c}.presized", r"最大 ([\d.]+) ms"))
    on, = map(int, get(f"{c}.default.ongrow", r"的：(\d+) 次"))
    assert on >= 7, (c, on)
    assert d_max > 20 * p_max and d_max * 1e6 > 10_000 * d_mean, (c, d_max, p_max, d_mean)
    print(f"通过：{c} 默认容量最慢 10 次有 {on} 次落在扩容点，最大 {d_max} ms，是均值 {d_mean} ns 的 {d_max * 1e6 / d_mean:,.0f} 倍；预分配后最大 {p_max} ms")
c_max, = map(float, get("map.capacity", r"最大 ([\d.]+) ms"))
c_top = re.search(r"^map\.capacity\.top\t最慢 10 次（\*为扩容点）：#([\d,]+)=", t, re.M).group(1)
assert c_top == "1,572,864" and c_max > 20 * p_max, (c_top, c_max, p_max)
print(f"通过：new HashMap<>(n) 最慢一次在第 {c_top} 次 put（{c_max} ms），newHashMap(n) 最大 {p_max} ms")
def lookup(prefix):
    rows = re.findall(rf"^{prefix}\.(\d+)\t.*顺序扫描 ([\d.]+) ns/次，HashMap ([\d.]+) ns/次", t, re.M)
    return {int(n): (float(s), float(h)) for n, s, h in rows}
def cross(r):
    return min(n for n, (s, h) in r.items() if s > h)
u, k, i = lookup("lookup"), lookup("lookup-skewed"), lookup("lookup-int")
for name, r in (("字符串均匀", u), ("字符串热键", k), ("int 均匀", i)):
    assert r[1][0] < r[1][1], (name, r[1])
    assert r[256][0] > 3 * r[256][1], (name, r[256])
assert cross(k) >= 4 * cross(u), (cross(u), cross(k))
print(f"通过：N=1 时顺序扫描都更快；均匀分布从 N={cross(u)} 起 HashMap 更快，90% 热键时推迟到 N={cross(k)}；N=256 时 HashMap 都快 3 倍以上")
PY
log "全部通过，输出在 $OUT"
