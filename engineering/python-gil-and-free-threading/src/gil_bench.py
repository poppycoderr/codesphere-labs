"""同一份代码在默认构建与 free-threaded 构建上的表现：CPU 密集的线程能否并行、阻塞等待、进程池、无锁计数。

用法：python gil_bench.py <标签>
"""
import sys
import sysconfig
import threading
import time
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor

WORK = 3_000_000


def out(key, fact):
    print(f"{key}\t{fact}", flush=True)


def cpu_task(n=WORK):
    total = 0
    for i in range(n):
        total += i * i % 7
    return total


def timed(fn):
    best = None
    for _ in range(5):                                   # 取五次里最快的一次，减少调度噪声
        t = time.perf_counter()
        fn()
        elapsed = time.perf_counter() - t
        best = elapsed if best is None else min(best, elapsed)
    return best


def run_threads(n, target):
    threads = [threading.Thread(target=target) for _ in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


def main(label):
    gil = sys._is_gil_enabled()
    out(f"{label}.build", f"Python {sys.version.split()[0]}，Py_GIL_DISABLED={sysconfig.get_config_var('Py_GIL_DISABLED')}，运行时 GIL 启用={gil}")

    cpu_task()                                           # 预热，让解释器完成对热点字节码的特化
    base = timed(lambda: run_threads(1, cpu_task))       # 基准也放在新线程里跑，与多线程的测法一致
    out(f"{label}.cpu.single_ms", f"1 个线程完成 1 份计算：{base * 1000:.0f}ms")
    for n in (2, 4):
        t = timed(lambda: run_threads(n, cpu_task))
        out(f"{label}.cpu.threads_{n}", f"{n} 个线程各完成 1 份计算：用时是单线程 1 份的 {t / base:.2f} 倍，相当于 {n * base / t:.2f} 个核在干活")

    t1 = timed(lambda: run_threads(1, lambda: time.sleep(0.2)))
    t4 = timed(lambda: run_threads(4, lambda: time.sleep(0.2)))
    out(f"{label}.io.threads_4", f"4 个线程各阻塞等待 200ms：用时是 1 个线程的 {t4 / t1:.2f} 倍")

    if label == "gil":
        with ProcessPoolExecutor(max_workers=4) as pool:
            list(pool.map(cpu_task, [10] * 4))           # 先把工作进程启动起来
            t = timed(lambda: list(pool.map(cpu_task, [WORK] * 4)))
        out(f"{label}.cpu.processes_4", f"4 个进程各完成 1 份计算：用时是单线程 1 份的 {t / base:.2f} 倍，相当于 {4 * base / t:.2f} 个核在干活")

    counter = 0
    lock = threading.Lock()

    def unsafe():
        nonlocal counter
        for _ in range(200_000):
            counter += 1

    def safe():
        nonlocal counter
        for _ in range(200_000):
            with lock:
                counter += 1

    run_threads(4, unsafe)
    lost = 800_000 - counter
    out(f"{label}.counter.unsafe", f"4 个线程各做 20 万次 counter += 1（不加锁）：结果 {'正好 800000' if lost == 0 else '少于 800000'}")
    counter = 0
    run_threads(4, safe)
    out(f"{label}.counter.locked", f"同样的计数加锁之后：结果 {counter}")

    items = []
    run_threads(4, lambda: [items.append(1) for _ in range(100_000)])
    out(f"{label}.list.append", f"4 个线程各向同一个 list append 10 万次（不加锁）：长度 {len(items)}")


if __name__ == "__main__":
    main(sys.argv[1])
