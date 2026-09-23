#!/usr/bin/env python3
"""断言表设计三个细节的关键结论。用法：summarize.py <输出目录>"""
import csv, re, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def text(n): return (out / n).read_text()

sd = text("soft-delete.txt")
check(re.search(r"A: two active rows allowed\? \|\s+2 \|", sd) is not None, "唯一索引包含可空的 deleted_at：两条未删除记录都插入成功")
check(re.search(r"\|\s+3 \|\s+0 \|", sd) is not None, "deleted_id 写法：两次退订后 id 1、2 的 deleted_id 为自身主键，id 3 为 0")
check("Duplicate entry '1001-VIP-0'" in text("soft-delete-duplicate-b.txt"), "deleted_id 写法：再插入有效记录报唯一键冲突")
check("Duplicate entry '1001:VIP' for key 'service_record_c.uk_active'" in text("soft-delete-duplicate-c.txt"), "函数索引写法：再插入有效记录报唯一键冲突")

ip = text("ip.txt")
def count(label): return int(re.search(rf"\| {re.escape(label)}\s+\|\s+(\d+) \|", ip).group(1))
binary, string, like = count("binary BETWEEN"), count("string BETWEEN"), count("string LIKE")
check(binary == like and string < binary, f"10.1.0.0/16：二进制 {binary} 行，字符串 BETWEEN {string} 行（少 {binary - string} 行，{(binary - string) / binary:.0%}），LIKE {like} 行")
check(count("binary /20") > 0, f"二进制可以查 /20 网段：{count('binary /20')} 行")
check(re.search(r"\|\s+1 \|\s+0 \|", ip) is not None, "'10.1.100.1' < '10.1.99.1' 在字符串比较中成立，二进制比较中不成立")
kb = dict(re.findall(r"\| (access_log_\w+) \| idx_ip\s+\|\s+(\d+) \|", ip))
check(int(kb["access_log_bin"]) < int(kb["access_log_str"]), f"idx_ip：二进制 {int(kb['access_log_bin']) / 1024:.1f}MB，字符串 {int(kb['access_log_str']) / 1024:.1f}MB")

hr = {(r["mode"], int(r["threads"])): r for r in csv.DictReader((out / "hot-row.tsv").open(), delimiter="\t")}
def tps(m, t): return int(hr[(m, t)]["tps"])
same64, spread64 = tps("same-row", 64), tps("spread-1000", 64)
check(tps("same-row", 64) < 2 * tps("same-row", 1), f"同一行：1 线程 {tps('same-row', 1)} TPS，64 线程 {same64} TPS，不到 2 倍")
for t in (16, 64):
    # 线程启动有先后，偶尔有一两次更新恰好不需要等锁，所以按比例断言
    check(int(hr[("same-row", t)]["row_lock_waits"]) >= 0.99 * int(hr[("same-row", t)]["updates"]), f"同一行 {t} 线程：几乎每次更新都经历锁等待（{hr[('same-row', t)]['row_lock_waits']} / {hr[('same-row', t)]['updates']}）")
check(spread64 > 5 * same64, f"64 线程分散到 1,000 行：{spread64} TPS，是同一行的 {spread64 / same64:.1f} 倍")
b4, b16 = tps("buckets-4", 64), tps("buckets-16", 64)
check(same64 < b4 < b16, f"64 线程分桶：4 桶 {b4} TPS（{b4 / same64:.1f}×），16 桶 {b16} TPS（{b16 / same64:.1f}×）")
if failures:
    sys.exit(f"{len(failures)} 项断言失败")
