#!/usr/bin/env python3
"""汇总 verify.sh 的输出并断言文章中的关键结论。用法：summarize.py <输出目录>
耗时取 EXPLAIN ANALYZE 根节点 actual time 的结束值；多次采样时取中位数。"""
import csv, re, statistics, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []

def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok:
        failures.append(msg)

class Plan:
    def __init__(self, d):
        self.name = d.name
        self.rows = list(csv.DictReader(d.joinpath("explain.tsv").open(), delimiter="\t"))
        self.first = self.rows[0]
        self.analyze = d.joinpath("explain-analyze.txt").read_text()
        self.sample = self.analyze.split("-- sample ")[1]
        roots = re.findall(r"^-> .*?actual time=[\d.e+-]+\.\.([\d.e+-]+)", self.analyze, re.M)
        self.median_ms = statistics.median(float(t) for t in roots)
        self.samples = len(roots)
        self.handler = {k: int(v) for k, v in (l.split("\t") for l in d.joinpath("handler.txt").read_text().splitlines())}
        self.icp = {k: int(v) for k, v in (l.split("\t") for l in d.joinpath("icp.txt").read_text().splitlines())}

    def actual_rows(self, needle):
        line = next(l for l in self.sample.splitlines() if needle in l)
        return int(float(re.search(r"actual time=\S+ rows=([\d.e+]+)", line).group(1)))

    def estimate_rows(self, needle):
        line = next(l for l in self.sample.splitlines() if needle in l)
        return int(float(re.search(r"\(cost=\S+ rows=([\d.e+]+)\)", line).group(1)))

p = {d.name: Plan(d) for d in sorted((out / "plans").iterdir())}

with open(out / "summary.md", "w") as f:
    f.write("<!-- 由 scripts/summarize.py 生成；EXPLAIN 各列来自 explain.tsv，耗时为 EXPLAIN ANALYZE 根节点结束时间（多次采样取中位数） -->\n\n")
    f.write("| 查询 | type | key | key_len | rows（估算） | filtered | Extra | Handler 读取 | ICP 检查 | 中位数 ms | 采样 |\n|---|---|---|---|---:|---:|---|---:|---:|---:|---:|\n")
    for x in p.values():
        r = x.first
        f.write(f"| `{x.name}` | {r['type']} | {r['key']} | {r['key_len']} | {r['rows']} | {r['filtered']} | {r['Extra']} | "
                f"{sum(x.handler.values()):,} | {x.icp.get('icp_attempts', 0):,} | {x.median_ms} | {x.samples} |\n")

def t(name, field): return p[name].first[field]

# 第二节：type、rows、key_len
check(t("a01-ref-customer", "type") == "ref" and t("a01-ref-customer", "key_len") == "4", "按客户查：ref，key_len = 4")
check(t("a02-all-created", "type") == "ALL", "按日期查：联合索引第一列不是 created_at，全表扫描")
check(t("a03-range-two-columns", "key_len") == "9" and "Using index condition" in t("a03-range-two-columns", "Extra"),
      "客户 + 日期：key_len = 9（INT 4 + DATETIME 5），Using index condition")
check(t("a07-phone-string", "key_len") == "82", "idx_phone 的 key_len = 82（VARCHAR(20) utf8mb4：20 × 4 + 2）")
# 第三节：Extra
check("Using index" in t("a04-covering", "Extra"), "只取 id 与 created_at：覆盖索引 Using index")
check("Using filesort" in t("a05-sort-amount", "Extra"), "按 amount 排序：Using filesort")
check("Backward index scan" in t("a06-sort-created", "Extra"), "按 created_at 倒序：Backward index scan，没有额外排序")
# 第四节：EXPLAIN ANALYZE
a09, b01 = p["a09-top-paid"], p["b01-top-paid-covering"]
check(a09.actual_rows("Index scan on orders using idx_customer_created") == 100000, "统计已支付客户：最内层扫描整棵 idx_customer_created，10 万行")
check(a09.actual_rows("Filter: (orders.`status` = 'PAID')") == 25000, "过滤后只剩 2.5 万行")
check(b01.actual_rows("Covering index lookup on orders using idx_status_customer") == 25000, "加 (status, customer_id) 后：覆盖索引只读 2.5 万行")
check(b01.median_ms * 5 < a09.median_ms, f"加索引后中位耗时 {a09.median_ms}ms → {b01.median_ms}ms，快 5 倍以上")
# 第五节：索引失效
for n in ["a10-date-function", "a12-column-arithmetic", "a13-like-suffix", "a17-or-one-unindexed", "a18-not-equal", "a08-phone-number"]:
    check(t(n, "type") == "ALL", f"{n}：全表扫描")
check(t("a08-phone-number", "possible_keys") == "idx_phone" and t("a08-phone-number", "key") == "NULL", "字符串列与数字比较：possible_keys 有 idx_phone，key 为 NULL")
check(t("a11-date-range", "type") == "ALL", "只改写成范围条件、没有 created_at 开头的索引：仍然全表扫描")
check(t("c01-date-range-indexed", "type") == "range" and t("c01-date-range-indexed", "key") == "idx_created", "补上 idx_created 后：范围扫描")
check(t("c02-date-function", "type") == "ALL", "有 idx_created 时，DATE(created_at) 仍然全表扫描")
check(p["c01-date-range-indexed"].actual_rows("Index range scan") == p["a10-date-function"].actual_rows("Filter"), "改写前后返回的行数相同")
check(t("a14-like-suffix-cover", "type") == "index" and t("a14-like-suffix-cover", "key") == "idx_phone", "前导通配符但只取索引列：type = index，扫描整棵 idx_phone")
check(t("a15-like-prefix", "type") == "range", "前缀匹配：range")
check(t("a16-or-both-indexed", "type") == "index_merge" and "sort_union" in t("a16-or-both-indexed", "Extra"), "OR 两边都有索引：index_merge（sort_union）")
# 调优篇第四节：低区分度索引
d01, d02 = p["d01-status-index"], p["d02-status-table-scan"]
check(d01.actual_rows("Index lookup") == d02.actual_rows("Filter") == 25000, "status = 'PAID'：走索引与全表扫描都返回 25,000 行")
check(max(d01.median_ms, d02.median_ms) < 2 * min(d01.median_ms, d02.median_ms), f"两者耗时同一量级（{d01.median_ms}ms 与 {d02.median_ms}ms）")
# 调优篇第三节：联合索引列顺序
e01, e02 = p["e01-equality-first"], p["e02-range-first"]
check(t("e01-equality-first", "key_len") == t("e02-range-first", "key_len") == "71", "两种列顺序的 key_len 都是 71（VARCHAR(16) 66 + DATETIME 5）")
check(e01.actual_rows("Index range scan") == e02.actual_rows("Index range scan"), "EXPLAIN ANALYZE 的实际行数相同：ICP 在引擎内过滤，差别不在这一列")
check(e02.icp["icp_attempts"] >= 3 * e01.icp["icp_attempts"],
      f"引擎检查的索引记录：(status, created_at) {e01.icp['icp_attempts']:,}，(created_at, status) {e02.icp['icp_attempts']:,}")
# 第 5.4 节：估算与实际
print(f"信息：一天范围无合适索引，估算输出 {int(a09 and p['a11-date-range'].estimate_rows('Filter'))} 行，实际 {p['a11-date-range'].actual_rows('Filter')} 行")
print(f"信息：status = 'PAID' 覆盖索引查找，估算 {b01.estimate_rows('Covering index lookup')} 行，实际 {b01.actual_rows('Covering index lookup')} 行")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
