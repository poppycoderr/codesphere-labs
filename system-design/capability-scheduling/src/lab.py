"""异构执行器的调度：任务要求某种能力（浏览器、设备），执行器各有能力标签。离散时间的确定性模拟：
只看队头的调度出现队头阻塞；按能力匹配后，增加执行器的收益取决于瓶颈在哪种能力、还是在调度器本身；执行器崩溃后，没有租约的任务永远不会完成。
输出 TSV（键、说明）。"""
from collections import Counter

DURATION = {"chrome": 2, "android": 6}


def out(key, text):
    print(f"{key}\t{text}", flush=True)


def jobs(n=40):
    """每 5 个任务里有 1 个需要 android，其余需要 chrome；全部在第 0 刻到达。"""
    return [(f"j{i}", "android" if i % 5 == 0 else "chrome") for i in range(n)]


def simulate(executors, policy, dispatch_per_tick=None, crash=None, lease=None, horizon=500):
    """executors：{能力: 数量}；policy：'head' 只看队头，'match' 在队列里找第一个有空闲执行器的任务。
    dispatch_per_tick：调度器每刻最多派发几个任务。crash：(时刻, 能力) 该刻一个正在运行该能力任务的执行器崩溃。
    lease：租约时长；有租约时，崩溃执行器上的任务在租约到期后重新入队。"""
    free = Counter(executors)
    busy = {c: 0 for c in executors}
    queue = jobs()
    running = []             # (结束时刻, 任务, 能力, 租约到期)
    lost, done, t = [], 0, 0
    busy_ticks = Counter()
    finish = {c: 0 for c in executors}      # 每种能力最后一个任务完成的时刻
    wait = {c: [] for c in executors}       # 每个任务从到达（第 0 刻）到开始执行的等待
    while (queue or running or lost) and t < horizon:
        # 崩溃：执行器连同它上面的任务消失，执行器数量减一
        if crash and crash[0] == t:
            victim = next(r for r in running if r[2] == crash[1])
            running.remove(victim)
            executors = {**executors, crash[1]: executors[crash[1]] - 1}
            lost.append((victim[1], victim[2], t + lease if lease else None))
        for item in [x for x in lost if x[2] is not None and x[2] <= t]:
            lost.remove(item)
            queue.insert(0, (item[0], item[1]))           # 租约到期，任务回到队首
        for r in [r for r in running if r[0] <= t]:
            running.remove(r)
            free[r[2]] += 1
            done += 1
            finish[r[2]] = max(finish[r[2]], t)
        budget = dispatch_per_tick or len(queue)
        while queue and budget:
            if policy == "head":
                pick = 0 if free[queue[0][1]] > 0 else None
            else:
                pick = next((i for i, (_, cap) in enumerate(queue) if free[cap] > 0), None)
            if pick is None:
                break
            job, cap = queue.pop(pick)
            free[cap] -= 1
            running.append((t + DURATION[cap], job, cap, None))
            wait[cap].append(t)
            budget -= 1
        for _, _, cap, _ in running:
            busy_ticks[cap] += 1
        if not queue and not running and all(x[2] is None for x in lost):
            break
        t += 1
    makespan = max(t, 1)
    util = {c: busy_ticks[c] / (executors[c] * makespan) for c in executors if executors[c]}
    mean_wait = {c: sum(w) / len(w) for c, w in wait.items() if w}
    return {"makespan": makespan, "done": done, "lost": len(lost), "util": util, "finish": finish, "wait": mean_wait}


def show(r):
    util = "、".join(f"{c} {u:.0%}" for c, u in r["util"].items())
    return f"全部完成 {r['makespan']} 刻，chrome 任务全部完成 {r['finish']['chrome']} 刻、平均等待 {r['wait']['chrome']:.1f} 刻，利用率 {util}"


base = {"chrome": 5, "android": 1}
out("load", f"40 个任务：android {sum(1 for _, c in jobs() if c == 'android')} 个（每个 6 刻）、chrome {sum(1 for _, c in jobs() if c == 'chrome')} 个（每个 2 刻）")
out("head.5", f"只看队头，chrome 5 台、android 1 台：{show(simulate(base, 'head'))}")
out("match.5", f"按能力匹配，chrome 5 台、android 1 台：{show(simulate(base, 'match'))}")
out("match.10", f"按能力匹配，chrome 加到 10 台：{show(simulate({'chrome': 10, 'android': 1}, 'match'))}（android 才是瓶颈）")
out("match.android2", f"按能力匹配，chrome 5 台、android 加到 2 台：{show(simulate({'chrome': 5, 'android': 2}, 'match'))}")
out("head.10", f"只看队头，chrome 加到 10 台：{show(simulate({'chrome': 10, 'android': 1}, 'head'))}（被排在前面的 android 任务挡住）")
out("dispatch.1", f"按能力匹配，chrome 10 台、android 4 台，调度器每刻只能派发 1 个：{show(simulate({'chrome': 10, 'android': 4}, 'match', dispatch_per_tick=1))}")
out("dispatch.unlimited", f"同样的执行器，调度器不限速：{show(simulate({'chrome': 10, 'android': 4}, 'match'))}")
r = simulate({"chrome": 5, "android": 2}, "match", crash=(3, "android"))
out("crash.nolease2", f"android 2 台、其中一台第 3 刻崩溃、没有租约：完成 {r['done']} 个，丢失 {r['lost']} 个")
r = simulate({"chrome": 5, "android": 2}, "match", crash=(3, "android"), lease=10)
out("crash.lease", f"同样崩溃、租约 10 刻：完成 {r['done']} 个，丢失 {r['lost']} 个，全部完成 {r['makespan']} 刻（只剩 1 台 android，比不崩溃时的 24 刻慢一倍）")
