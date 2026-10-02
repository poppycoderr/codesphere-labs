# asyncio 任务的生命周期

对应文章：[python-asyncio-task-lifecycle.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/python-asyncio-task-lifecycle.md)。

`src/lifecycle.py` 只用标准库，在固定 digest 的 Python 3.14.8 容器里运行。被观察的协程每 5ms 累加一次 tick；发生失败、取消或超时之后隔 100ms 再看 tick 是否增长，以此判断协程本身有没有停下。

1. `asyncio.gather` 中一个任务失败（默认与 `return_exceptions=True`）；
2. `asyncio.TaskGroup` 中一个任务失败；
3. `task.cancel()`：刚返回时的状态、让出事件循环之后的状态、协程吞掉 `CancelledError` 时；
4. `asyncio.wait_for` 与 `asyncio.timeout`：正常协程与吞掉取消的协程；
5. 不保存 `create_task` 返回值的任务在垃圾回收后的日志；后台任务抛出异常且无人等待；
6. 协程里直接调用 `time.sleep` 与改用 `asyncio.to_thread` 时，另一个心跳协程的最大间隔；
7. 100 个协程各自「读、（让出）、写」同一个计数。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
