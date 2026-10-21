"""交付周期的口径：从工作项的状态事件重建周期、等待与在制工作。合成事件覆盖重复投递、乱序到达、日内重开、取消与未完成，
对照每日快照与只统计已完成项的错误口径。时间单位为小时，输出 TSV（键、说明）。"""
from statistics import median

DAY = 24
# (事件 ID, 工作项, 发生时刻（小时）, 到达时刻, 新状态)；状态：ready 排队 → doing 处理 → review 等评审 → done，另有 cancelled
EVENTS = [
    ("e1", "A", 0, 0, "ready"), ("e2", "A", 30, 30, "doing"), ("e3", "A", 40, 41, "review"), ("e4", "A", 70, 70, "done"),
    ("e5", "B", 2, 2, "ready"), ("e6", "B", 4, 4, "doing"), ("e7", "B", 10, 10, "review"), ("e8", "B", 12, 12, "done"),
    ("e9", "B", 14, 14, "doing"), ("e10", "B", 20, 20, "done"),                   # 当天上午完成、下午重开、晚上再完成
    ("e11", "C", 5, 5, "ready"), ("e12", "C", 50, 50, "doing"), ("e13", "C", 54, 90, "review"),   # 评审事件晚到 36 小时
    ("e14", "C", 60, 61, "done"),
    ("e15", "D", 6, 6, "ready"), ("e16", "D", 8, 8, "doing"), ("e16", "D", 8, 9, "doing"),       # 同一事件重复投递
    ("e17", "D", 30, 30, "cancelled"),
    ("e18", "E", 10, 10, "ready"), ("e19", "E", 20, 20, "doing"), ("e20", "E", 26, 26, "review"),  # 未完成
    ("e21", "F", 12, 12, "ready"), ("e22", "F", 13, 13, "doing"), ("e23", "F", 15, 15, "review"), ("e24", "F", 16, 16, "done"),
    ("e25", "G", 1, 1, "ready"),                                                                    # 一直在排队
]
NOW = 96
ACTIVE = {"doing"}
WAITING = {"ready", "review"}


def out(key, text):
    print(f"{key}\t{text}", flush=True)


def fmt(values):
    return "、".join(f"{k} {v:g}h" for k, v in values.items())


def histories(events, by="occurred", dedupe=True):
    """按工作项整理状态历史；by 选择排序依据（发生时刻或到达时刻）。"""
    seen, items = set(), {}
    for eid, item, occurred, arrived, state in sorted(events, key=lambda e: e[2] if by == "occurred" else e[3]):
        if dedupe and eid in seen:
            continue
        seen.add(eid)
        items.setdefault(item, []).append((occurred, state))
    return items


def first(hist, state):
    return next((t for t, s in hist if s == state), None)


def last(hist, state):
    return next((t for t, s in reversed(hist) if s == state), None)


def time_in(hist, states, end):
    """逐段累计处在某些状态的时长，最后一段算到 end。"""
    total = 0
    for (t, s), nxt in zip(hist, hist[1:] + [(end, None)]):
        if s in states:
            total += nxt[0] - t
    return total


h = histories(EVENTS)
finished = {k: v for k, v in h.items() if v[-1][1] == "done"}

# 1. 起点不同，周期不同
from_created = {k: last(v, "done") - first(v, "ready") for k, v in finished.items()}
from_started = {k: last(v, "done") - first(v, "doing") for k, v in finished.items()}
out("start.created", f"从进入 ready 算起：{fmt(from_created)}；中位数 {median(from_created.values()):g}h")
out("start.doing", f"从第一次进入 doing 算起：{fmt(from_started)}；中位数 {median(from_started.values()):g}h")

# 2. 重开：第一次完成还是最后一次完成
b = h["B"]
out("reopen", f"B 第一次完成在 {first(b, 'done')}h、重开后最后完成在 {last(b, 'done')}h：按第一次完成算周期 {first(b, 'done') - first(b, 'ready')}h，按最后一次算 {last(b, 'done') - first(b, 'ready')}h；重开次数 {sum(1 for (_, s), (_, n) in zip(b, b[1:]) if s == 'done' and n == 'doing')}")

# 3. 每日快照丢掉日内往返
snapshot_b = [next(s for t, s in reversed(b) if t <= d * DAY - 1e-9) if any(t <= d * DAY - 1e-9 for t, _ in b) else "-" for d in (1, 2)]
out("snapshot", f"B 在每天结束时的快照：{snapshot_b}；事件历史里同一天经历了 {[s for _, s in b]}，快照看不到重开")

# 4. 活动与等待
for k in ("A", "C", "F"):
    v = h[k]
    end = last(v, "done")
    active, waiting = time_in(v, ACTIVE, end), time_in(v, WAITING, end)
    out(f"flow.{k}", f"{k}：处理 {active:g}h、等待 {waiting:g}h（排队与等评审），处理时间占端到端 {active / (active + waiting):.0%}")

# 5. 只统计已完成项
open_items = {k: v for k, v in h.items() if v[-1][1] not in ("done", "cancelled")}
ages = {k: NOW - first(v, "ready") for k, v in open_items.items()}
out("survivor", f"只统计已完成的 {len(finished)} 项，中位周期 {median(from_created.values()):g}h；在第 {NOW}h 时仍未完成的 {len(open_items)} 项已等待 {fmt(ages)}，"
                f"其中 G 从未开始；取消 {sum(1 for v in h.values() if v[-1][1] == 'cancelled')} 项（D）不计入周期")

# 6. 乱序与重复
by_arrival = histories(EVENTS, by="arrived")
c_arrival = [s for _, s in by_arrival["C"]]
out("order.arrival", f"按到达顺序处理 C 的事件：{c_arrival}，最后状态 {c_arrival[-1]}（晚到的评审事件把已完成的工作项拉回 review）")
out("order.occurred", f"按发生时刻排序：{[s for _, s in h['C']]}，最后状态 {h['C'][-1][1]}")
raw_d = histories(EVENTS, dedupe=False)["D"]
out("dedupe", f"D 的事件不去重：{[s for _, s in raw_d]}；按事件 ID 去重：{[s for _, s in h['D']]}")

# 7. 在制工作
def state_at(hist, t):
    return next((s for tt, s in reversed(hist) if tt <= t), None)


wip = {t: sum(1 for v in h.values() if state_at(v, t) in ACTIVE | WAITING) for t in (12, 14, 24, 48, 72, 96)}
out("wip", f"各时刻处在 ready、doing、review 的工作项数：{wip}（B 在 12h 完成、14h 重开，又回到在制）")
