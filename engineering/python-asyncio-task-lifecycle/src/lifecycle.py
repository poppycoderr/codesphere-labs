"""asyncio 任务的生命周期：失败、取消、超时之后，其他任务和任务本身处于什么状态。

每个被观察的任务持续累加自己的 tick；发生失败或取消后隔一段时间再看 tick 是否还在增长。
"""
import asyncio
import gc
import io
import logging
import time


def out(key, fact):
    print(f"{key}\t{fact}", flush=True)


class Work:
    """一个可观察的协程：每 5ms 一次 tick，最多运行 seconds 秒。"""

    def __init__(self, seconds=1.0, swallow_cancel=False):
        self.ticks = 0
        self.seconds = seconds
        self.swallow_cancel = swallow_cancel
        self.exit = "仍在运行"
        self.cleanup_ran = False

    async def run(self):
        end = time.monotonic() + self.seconds
        try:
            while time.monotonic() < end:
                self.ticks += 1
                try:
                    await asyncio.sleep(0.005)
                except asyncio.CancelledError:
                    if not self.swallow_cancel:
                        raise
            self.exit = "跑完全程"
            return "done"
        except asyncio.CancelledError:
            self.exit = "收到 CancelledError 后退出"
            raise
        finally:
            self.cleanup_ran = True

    async def observe(self):
        before = self.ticks
        await asyncio.sleep(0.1)
        return "仍在运行" if self.ticks > before else f"已停止（{self.exit}）"


async def fail_after(delay):
    await asyncio.sleep(delay)
    raise ValueError("boom")


async def gather_failure():
    a, c = Work(), Work()
    try:
        await asyncio.gather(a.run(), fail_after(0.05), c.run())
        result = "正常返回"
    except ValueError as e:
        result = f"抛出 {type(e).__name__}"
    out("gather.default", f"gather 中一个任务 50ms 后失败：gather {result}；另外两个任务 {await a.observe()}、{await c.observe()}")

    a, c = Work(0.2), Work(0.2)
    t = time.monotonic()
    results = await asyncio.gather(a.run(), fail_after(0.05), c.run(), return_exceptions=True)
    out("gather.return_exceptions", f"return_exceptions=True：等了 {round((time.monotonic() - t) * 1000, -2):.0f}ms 全部结束后返回 "
        f"[{', '.join(type(r).__name__ if isinstance(r, Exception) else repr(r) for r in results)}]")


async def task_group_failure():
    a, c = Work(), Work()
    try:
        async with asyncio.TaskGroup() as tg:
            tg.create_task(a.run())
            tg.create_task(fail_after(0.05))
            tg.create_task(c.run())
        result = "正常退出"
    except* ValueError as eg:
        result = f"抛出 {type(eg).__name__}，内含 {[type(e).__name__ for e in eg.exceptions]}"
    out("taskgroup", f"TaskGroup 中一个任务 50ms 后失败：{result}；离开 async with 时另外两个任务 {await a.observe()}、{await c.observe()}，finally 已执行={a.cleanup_ran and c.cleanup_ran}")


async def cancellation():
    w = Work()
    task = asyncio.create_task(w.run())
    await asyncio.sleep(0.03)
    task.cancel()
    out("cancel.requested", f"cancel() 刚返回时：task.done()={task.done()}，task.cancelled()={task.cancelled()}")
    await asyncio.sleep(0)
    await asyncio.sleep(0)
    out("cancel.normal", f"让出事件循环之后：task.cancelled()={task.cancelled()}，{await w.observe()}，finally 已执行={w.cleanup_ran}")

    w = Work(0.4, swallow_cancel=True)
    task = asyncio.create_task(w.run())
    await asyncio.sleep(0.03)
    task.cancel()
    await asyncio.sleep(0.05)
    state = await w.observe()
    await asyncio.wait([task])
    out("cancel.swallowed", f"协程捕获 CancelledError 后继续循环：cancel() 之后 {state}；最终 task.cancelled()={task.cancelled()}，结果 {task.result()!r}")


async def timeouts():
    w = Work()
    t = time.monotonic()
    try:
        await asyncio.wait_for(w.run(), timeout=0.05)
        r = "正常返回"
    except TimeoutError:
        r = "抛出 TimeoutError"
    out("wait_for.normal", f"wait_for(…, 0.05)：{r}，用时约 {round((time.monotonic() - t) * 1000, -1):.0f}ms；任务 {await w.observe()}")

    w = Work(0.4, swallow_cancel=True)
    t = time.monotonic()
    try:
        r = f"正常返回 {await asyncio.wait_for(w.run(), timeout=0.05)!r}"
    except TimeoutError:
        r = "抛出 TimeoutError"
    out("wait_for.swallowed", f"协程吞掉取消时 wait_for(…, 0.05)：{r}，用时约 {round((time.monotonic() - t) * 1000, -2):.0f}ms（任务自己要跑 400ms）")

    w = Work()
    try:
        async with asyncio.timeout(0.05):
            await w.run()
        r = "正常退出"
    except TimeoutError:
        r = "抛出 TimeoutError"
    out("timeout.context", f"async with asyncio.timeout(0.05)：{r}；任务 {await w.observe()}")


async def lost_task():
    """只创建不保存引用的任务：事件循环只持有弱引用。"""
    log = io.StringIO()
    handler = logging.StreamHandler(log)
    logging.getLogger("asyncio").addHandler(handler)
    finished = []

    async def waits_on_private_future():
        await asyncio.get_running_loop().create_future()      # 只有这个协程自己引用这个 Future
        finished.append(True)

    asyncio.create_task(waits_on_private_future())            # 没有保存返回值
    await asyncio.sleep(0.01)
    gc.collect()
    await asyncio.sleep(0.01)
    out("lost.no_reference", f"不保存 create_task 的返回值，垃圾回收之后：asyncio 日志 {log.getvalue().strip().splitlines()[0] if log.getvalue() else '（无）'!r}")

    log.truncate(0); log.seek(0)
    kept = asyncio.create_task(waits_on_private_future())
    await asyncio.sleep(0.01)
    gc.collect()
    await asyncio.sleep(0.01)
    out("lost.kept_reference", f"保存了引用，垃圾回收之后：任务 done()={kept.done()}，asyncio 日志 {log.getvalue().strip() or '（无）'!r}")
    kept.cancel()

    log.truncate(0); log.seek(0)
    held = [asyncio.create_task(fail_after(0))]              # 后台任务失败；引用还在，但没有人 await 它
    await asyncio.sleep(0.01)
    gc.collect()
    while_held = log.getvalue().strip() or "（无）"
    held.clear()                                              # 引用释放，任务对象被销毁
    gc.collect()
    await asyncio.sleep(0.01)
    out("lost.exception", f"后台任务抛出异常且无人等待：引用还在时 asyncio 日志 {while_held!r}；引用释放后日志首行 {log.getvalue().strip().splitlines()[0]!r}")
    logging.getLogger("asyncio").removeHandler(handler)


async def blocking_call():
    async def heartbeat(gaps):
        last = time.monotonic()
        while True:
            await asyncio.sleep(0.01)
            now = time.monotonic()
            gaps.append(now - last)
            last = now

    async def direct():
        time.sleep(0.3)                                       # 阻塞调用：事件循环线程被占住

    async def offloaded():
        await asyncio.to_thread(time.sleep, 0.3)              # 放到线程里执行，事件循环继续运转

    for key, label, call in (("direct", "time.sleep(0.3)", direct), ("to_thread", "asyncio.to_thread(time.sleep, 0.3)", offloaded)):
        gaps = []
        hb = asyncio.create_task(heartbeat(gaps))
        await asyncio.sleep(0.05)
        await call()
        await asyncio.sleep(0.05)
        hb.cancel()
        worst = max(gaps) * 1000
        out("blocking." + key,
            f"协程里调用 {label}：每 10ms 一次的心跳，最大间隔 {'不少于 300ms' if worst >= 300 else '小于 100ms' if worst < 100 else f'{worst:.0f}ms'}")


async def shared_state():
    for with_await in (True, False):
        counter = 0

        async def add():
            nonlocal counter
            current = counter
            if with_await:
                await asyncio.sleep(0)                         # 读和写之间让出事件循环
            counter = current + 1

        await asyncio.gather(*(add() for _ in range(100)))
        out("race." + ("with_await" if with_await else "no_await"), f"100 个协程各加 1，读和写之间{'有' if with_await else '没有'} await：结果 {counter}")


async def main():
    await gather_failure()
    await task_group_failure()
    await cancellation()
    await timeouts()
    await lost_task()
    await blocking_call()
    await shared_state()


if __name__ == "__main__":
    import sys
    out("env", f"python={sys.version.split()[0]}")
    asyncio.run(main())
