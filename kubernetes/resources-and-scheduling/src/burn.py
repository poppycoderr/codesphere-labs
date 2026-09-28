"""模拟请求：每个任务做固定次数的计算，任务之间空闲 GAP 毫秒；输出任务耗时分位数、平均 CPU 占用与 cgroup 的限流统计。"""
import sys
import time

work, tasks, gap = int(sys.argv[1]), int(sys.argv[2]), float(sys.argv[3]) / 1000 if len(sys.argv) > 3 else 0


def task():
    x = 0
    for i in range(work):
        x += i
    return x


def cpu_stat():
    return {k: int(v) for k, v in (l.split() for l in open("/sys/fs/cgroup/cpu.stat"))}


if work == 0:                                  # 标定：不限 CPU 时 10 万次循环耗时多少毫秒
    work = 100_000
    t = time.monotonic()
    for _ in range(20):
        task()
    print(f"ms_per_100k={(time.monotonic() - t) * 1000 / 20:.3f}")
    sys.exit()
for _ in range(5):                             # 预热
    task()
    time.sleep(gap)
s0, w0 = cpu_stat(), time.monotonic()
durations = []
for _ in range(tasks):
    t = time.monotonic()
    task()
    durations.append((time.monotonic() - t) * 1000)
    time.sleep(gap)
s1, wall = cpu_stat(), time.monotonic() - w0
durations.sort()
pct = lambda p: durations[min(len(durations) - 1, int(len(durations) * p))]
print(f"p50={pct(0.5):.0f}ms p99={pct(0.99):.0f}ms 平均占用={(s1['usage_usec'] - s0['usage_usec']) / 1e6 / wall:.2f}核 "
      f"被限流周期={s1['nr_throttled'] - s0['nr_throttled']}/{s1['nr_periods'] - s0['nr_periods']}")
