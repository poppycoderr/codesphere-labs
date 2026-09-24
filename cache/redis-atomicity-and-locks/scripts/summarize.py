#!/usr/bin/env python3
"""断言原子性、事务与锁实验的结果。用法：summarize.py <输出目录>"""
import sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
res, timelines = {}, {}
for name in ("main", "before-restart", "after-restart"):
    for line in (out / f"{name}.tsv").read_text().splitlines():
        k, v = line.split("\t", 1)
        if k.endswith(".timeline"): timelines.setdefault(k, []).append(v)
        else: res[k] = v

q = lambda name, f: res[f"quota.{name}.{f}"]
g = "get-then-set"
check(int(q(g, "client_won")) > 100 and int(q(g, "winners_list")) > 100,
      f"GET 判断后 SET：100 个名额，50 个线程各抢 10 次，客户端认为抢到 {q(g, 'client_won')} 次，名单 {q(g, 'winners_list')} 人，剩余名额 {q(g, 'left')}——超卖 {int(q(g, 'winners_list')) - 100} 个")
for name, desc in (("decr-then-compensate", "DECR 后小于 0 再 INCR 补回"), ("watch-multi", "WATCH + MULTI 冲突重试"), ("lua", "Lua 条件扣减"), ("function", "Function 条件扣减")):
    check(q(name, "client_won") == "100" and q(name, "winners_list") == "100" and q(name, "left") in ("0",),
          f"{desc}：抢到 100 次、名单 100 人、剩余 {q(name, 'left')}，耗时 {q(name, 'elapsed_ms')}ms" + (f"，WATCH 冲突重试 {int(q(name, 'watch_retries')):,} 次" if name == "watch-multi" else ""))
check(int(q("watch-multi", "watch_retries")) > 100, f"WATCH 在 50 个并发下冲突重试 {int(q('watch-multi', 'watch_retries')):,} 次，耗时 {q('watch-multi', 'elapsed_ms')}ms，Lua 为 {q('lua', 'elapsed_ms')}ms")
dl = q("decr-then-compensate", "left")
print(f"信息：DECR 补偿写法结束时剩余 {dl}（中途会短暂出现负数）")

check(res["tx.queue_error.exec"].startswith("EXECABORT") and res["tx.queue_error.tx:a_after"] == "null",
      f"入队时命令错误（{res['tx.queue_error.bad_command'][:48]}…）：EXEC 返回 EXECABORT，队列里的 SET 也没有执行")
ex = res["tx.runtime_error.exec_replies"]
check("WRONGTYPE" in ex and res["tx.runtime_error.tx:k_after"] == "1" and res["tx.runtime_error.tx:k2_after"] == "2",
      f"执行时出错（对字符串 LPUSH）：EXEC 返回 {ex}，出错命令前后的 SET 都已生效，没有回滚")
check(res["tx.discard"] == "OK" and res["tx.discard.tx:a_after"] == "0", "DISCARD 放弃队列，值保持不变")
check(res["tx.watch_conflict.exec"] == "null" and res["tx.watch_conflict.tx:a_after"] == "changed-by-other", "WATCH 的 key 在 EXEC 前被其他连接修改：EXEC 返回空，整个事务不执行")

check(res["restart.before.evalsha"] == "1" and res["restart.before.fcall"] == "1", "重启前 EVALSHA 与 FCALL 都能执行")
check(res["restart.after.evalsha"].startswith("NOSCRIPT") and res["restart.after.fcall"] == "1",
      f"重启后 EVALSHA 返回 {res['restart.after.evalsha'][:40]}…，FCALL 仍可执行（Function 随 AOF 持久化）；名额从 5 扣到 {res['restart.after.quota_left']}")

check(res["lock.a_acquire"] == "OK" and res["lock.b_acquire_while_held"] == "null" and res["lock.b_acquire_after_expiry"] == "OK",
      "SET NX PX：A 持有时 B 获取失败；300ms 租约过期后 B 获取成功")
check(res["lock.a_naive_del_removes_b_lock"] == "1" and res["lock.holder_after_naive_del"] == "null",
      "A 不校验 token 直接 DEL：删掉的是 B 的锁")
check(res["lock.a_token_release"] == "0" and res["lock.holder_after_token_release"] == "token-b" and res["lock.b_token_release"] == "1",
      "比较 token 再删除的 Lua：A 释放返回 0、B 的锁仍在；B 释放返回 1")

un, fe = timelines["lease.unfenced.timeline"], timelines["lease.fenced.timeline"]
check(res["lease.unfenced.final_value"] == "written-by-A" and any("A resumes, lock holder is now token-b" in t for t in un),
      "租约 500ms、A 停顿 1500ms：B 在 A 的锁过期后获取并写入，A 恢复后仍写入下游，最终值是旧持有者 A 写的 → " + " | ".join(un))
check(res["lease.fenced.final_value"] == "written-by-B" and any("A writes downstream -> 0" in t for t in fe),
      "加入 fencing token：下游只接受不小于已见最大 token 的写入，A 的写入被拒绝，最终值是 B 写的 → " + " | ".join(fe))

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
