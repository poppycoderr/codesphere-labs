#!/usr/bin/env python3
"""对 facts.tsv 断言重平衡实验的关键关系，并汇总两轮扩容的取值范围。用法：check.py <facts.tsv>"""
import re, sys
from collections import defaultdict

text = open(sys.argv[1], encoding="utf-8").read()
def lines(prefix):
    return [l.split("\t", 1)[1] for l in text.splitlines() if l.split("\t", 1)[0] == prefix]
def ok(cond, msg):
    if not cond:
        sys.exit("失败：" + msg)
    print("通过：" + msg)

ok("group.protocol=classic，partition.assignment.strategy=[RangeAssignor, CooperativeStickyAssignor]" in text, "客户端默认：经典协议，分配器列表第一个是 RangeAssignor")
ok("ConfigException：session.timeout.ms cannot be set when group.protocol=CONSUMER" in text, "新协议下设置 session.timeout.ms 直接抛出 ConfigException")

split = defaultdict(list)
for mode in ("eager", "coop", "consumer"):
    for kind in ("healthy", "slow"):
        for l in lines(f"scale.{mode}.{kind}.split"):
            m = re.search(r"所有者没变的分区 \[[^\]]*\] 最长中断 (\d+) ms；换了所有者的分区 \[[^\]]*\] 最长中断 (\d+) ms", l)
            split[(mode, kind)].append((int(m[1]), int(m[2])))
revoked = {mode: [int(re.search(r"被撤销的分区 (\d+) 个", l)[1]) for l in lines(f"scale.{mode}.healthy")] for mode in ("eager", "coop", "consumer")}
detail = {mode: lines(f"scale.{mode}.slow.detail") for mode in ("eager", "coop", "consumer")}
c1kept = {mode: [max(map(int, re.findall(r"\d+", re.search(r"C1 保留的分区 \[[^\]]*\] 最长中断 \[([^\]]*)\]", l)[1])), default=0) for l in detail[mode]] for mode in detail}

ok(revoked["eager"] == [6, 6], "eager：扩容时 6 个分区全部被撤销（两轮）")
ok(all(r == 2 for r in revoked["coop"] + revoked["consumer"]), "协作式与新协议：只撤销要移走的 2 个分区（两轮）")
ok(all(max(k, m) < 1000 for k, m in split[("eager", "healthy")]), "eager、成员都健康：全组停顿不到 1 秒")
ok(all(k < 300 and m > 1000 for k, m in split[("coop", "healthy")]),
   "协作式、成员都健康：保留的分区几乎不停，被移动的分区停顿超过 1 秒")
ok(all(k < 300 and m > 300 for k, m in split[("consumer", "healthy")]),
   "新协议、成员都健康：保留的分区几乎不停，被移动的分区有停顿（长短取决于心跳周期）")
ok(all(v > 5000 for v in c1kept["eager"]), "eager、有慢成员：健康成员 C1 保留的分区也陪着停了 5 秒以上")
ok(all(v < 500 for v in c1kept["coop"] + c1kept["consumer"]), "协作式与新协议、有慢成员：C1 保留的分区不受影响")
ok(all("扩容后首次分配" in l and "（慢处理结束之后）" in l.split("扩容后首次分配")[1] for l in detail["eager"] + detail["coop"]),
   "eager 与协作式：慢成员处理期间，组里没有完成任何重新分配")
ok(all("（慢处理结束之后）" in l.split("C1 首次撤销")[1].split("；")[0] for l in detail["coop"]), "协作式：连 C1 交出分区都要等慢成员结束")
ok(all("（慢处理结束之前）" in l.split("C1 首次撤销")[1].split("；")[0] for l in detail["consumer"]), "新协议：慢成员还在处理时，C1 已经交出要移走的分区")

dup = lines("dup.eager")[0]
ok(re.search(r"其中 [1-9]\d* 条被处理了两次或以上；提交失败 [1-9]", dup) is not None, "经典协议：处理超时被移出组后，没提交的一批被重复处理，提交失败")
for mode in ("eager", "consumer"):
    s = lines(f"member.{mode}.static")[0]
    ok("C2 被撤销 0 次、分配 0 次" in s, f"{mode} 静态成员：C1 重启期间 C2 没有任何撤销与分配")
    d = lines(f"member.{mode}.dynamic")[0]
    ok(re.search(r"C2 被撤销 [1-9]", d) is not None, f"{mode} 动态成员：C1 重启期间 C2 经历了撤销与重新分配")

def rng(vals):
    return f"{min(vals)}～{max(vals)}" if min(vals) != max(vals) else str(vals[0])
print("\n汇总（两轮取值范围，毫秒）：")
for (mode, kind), v in sorted(split.items()):
    print(f"  {mode}.{kind}：所有者没变的分区最长中断 {rng([k for k, _ in v])}；换了所有者的分区最长中断 {rng([m for _, m in v])}")
for mode in detail:
    moved = [x for l in detail[mode] for x in map(int, re.findall(r"\d+", re.search(r"从 C1 移给 C3 的分区 \[[^\]]*\] 最长中断 \[([^\]]*)\]", l)[1]))]
    print(f"  {mode}.slow：C1 保留的分区最长中断 {rng(c1kept[mode])}；从 C1 移给 C3 的分区最长中断 {rng(moved) if moved else '（两轮都没有从 C1 移走分区）'}")
