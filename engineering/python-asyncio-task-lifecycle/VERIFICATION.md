# 验证记录：asyncio 任务的生命周期

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

CPython 3.14.8。

1. **gather**：一个任务 50ms 后失败，`gather` 抛出 `ValueError`，另外两个任务仍在运行。`return_exceptions=True` 时等了 200ms（全部任务结束）后返回 `['done', ValueError, 'done']`。
2. **TaskGroup**：一个任务失败，抛出 `ExceptionGroup`（内含 `ValueError`）；离开 `async with` 时另外两个任务已收到 `CancelledError` 并退出，`finally` 已执行。
3. **cancel**：`cancel()` 刚返回时 `done()` 与 `cancelled()` 都是 False；让出事件循环后 `cancelled()` 为 True，协程已退出且 `finally` 已执行。协程捕获 `CancelledError` 后继续循环时，取消后仍在运行，最终 `cancelled()` 为 False，结果 `'done'`。
4. **超时**：`wait_for(…, 0.05)` 约 50ms 抛出 `TimeoutError`，内部协程已退出。协程吞掉取消时，`wait_for` 约 400ms 后正常返回 `'done'`，没有抛出超时。`asyncio.timeout(0.05)` 抛出 `TimeoutError`，协程已退出。
5. **任务引用**：不保存 `create_task` 的返回值，垃圾回收后 asyncio 日志为 `Task was destroyed but it is pending!`；保存引用时任务未结束、没有日志。后台任务抛出异常且无人等待：引用还在时没有日志，引用释放后日志首行为 `Task exception was never retrieved`。
6. **阻塞调用**：协程里 `time.sleep(0.3)`，每 10ms 一次的心跳最大间隔不少于 300ms；改用 `asyncio.to_thread` 后小于 100ms。
7. **共享状态**：100 个协程各加 1，读和写之间有 `await` 时结果为 1，没有时为 100。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/lifecycle.py`。「丢失引用」的场景里，协程等待一个只有它自己引用的 Future，使任务与 Future 构成只靠循环引用存活的对象，`gc.collect()` 后必然被回收；等待 `asyncio.sleep` 的任务还被事件循环的定时器间接引用，不会这样消失。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。连续运行 4 次，输出逐字相同。

## 五、误差、限制与不能推出的结论

- 耗时按 10ms 或 100ms 取整后输出，只用于区分「约 50ms」与「约 400ms」这类量级。
- 「丢失引用」是否发生取决于任务还被谁引用；本实验构造的是必然被回收的情形，实际代码里表现为偶发。
- 只在 CPython 3.14.8 上验证；`wait_for` 的实现在 3.12 有过调整，更早版本的行为没有验证。
- 没有覆盖 `asyncio.shield`、`loop.run_in_executor` 的取消语义与第三方事件循环。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
