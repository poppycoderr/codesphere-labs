#!/usr/bin/env python3
"""断言分布式信号量实验的结果。用法：summarize.py <输出目录>"""
import sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
res, timeline = {}, []
for line in (out / "results.tsv").read_text().splitlines():
    k, v = line.split("\t", 1)
    if k == "lease.timeline": timeline.append(v)
    else: res[k] = v

check(res["acquire.pod-3"] == "available=0 holders=3", "容量 3：pod-1、pod-2、pod-3 依次获取后可用许可为 0、持有者 3 个")
w = int(res["acquire.pod-4.waited_ms"])
check(res["acquire.pod-4.result"] == "timeout" and 2000 <= w < 2500, f"pod-4 的 BRPOP 等待 2 秒后返回空，实际等待 {w}ms")
check(res["release.pod-1.first"].startswith("1 ") and res["release.pod-1.second"] == "0 available=1 holders=2",
      f"释放 Lua 以 ZREM 的返回值判断：第一次返回 1（{res['release.pod-1.first'][2:]}），重复释放返回 0，许可数不变")
check(res["compensate.after_pod-2_lease_expired"] == "1 available=2 holders=1", "pod-2 的租约改为已过期后，补偿脚本回收 1 个许可：可用 2、持有者 1")
check(res["release.pod-2.after_reclaimed"].startswith("0 "), "pod-2 在被回收后再释放返回 0，不会把许可多放回一次")
check(res["release.pod-3"] == "1 available=3 holders=0" and res["compensate.when_balanced"].startswith("0 "), "全部释放后账目为可用 3、持有者 0，此时补偿不补任何许可")
check(res["pop_crash.before_compensate"] == "available=2 holders=0" and res["pop_crash.compensate"] == "1 available=3 holders=0",
      "BRPOP 取走许可后、ZADD 之前崩溃：许可既不在 List 也没有持有者记录；按「容量 - 可用 - 持有」补齐后恢复为 3")
check(res["stress.max_concurrent_holders"] == "3" and res["stress.acquired"] == "1000" and res["stress.final"] == "available=3 holders=0" and res["stress.double_release_succeeded"] == "0",
      f"20 个线程各获取、释放 50 次（每次持有 2—5ms）：共获取 {res['stress.acquired']} 次、超时 {res['stress.timeouts']} 次，同时持有许可的最多 {res['stress.max_concurrent_holders']} 个，重复释放全部返回 0，结束后可用 3、持有者 0")
check(res["lease.max_concurrent_holders"] == "2" and any("pod-b acquired=true, pod-a still running=true" in t for t in timeline) and any("pod-a finished, release=0" in t for t in timeline),
      "容量 1、租约 500ms、任务 1500ms：补偿在 " + next(t.split(" ")[0] for t in timeline if "compensate" in t) + " 回收许可后 pod-b 立即获取，此时 pod-a 仍在运行，同时有 2 个持有者；pod-a 结束时释放返回 0")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
