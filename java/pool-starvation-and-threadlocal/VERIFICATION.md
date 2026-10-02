# 验证记录：同池等待子任务与 ThreadLocal 残留

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.4。

1. **同一个池里等子任务**：2 线程的固定线程池，2 个父任务各自向同一个池提交子任务并等待，500ms 内完成 0/2；池内活跃线程 2，队列里等待的任务 2。两个线程都在等队列里的子任务，而子任务没有线程可用。
2. **子任务用单独的池**：500ms 内完成 2/2。**每任务一个虚拟线程**：完成 2/2。
3. **ThreadLocal 残留**：单线程池上任务 A `set("alice")` 后不清理，任务 B 读到 `alice`；任务在 `finally` 里 `remove()` 后，下一个任务读到 `null`。
4. **ScopedValue**：`ScopedValue.where(USER, "carol").run(…)` 结束后，下一个任务里 `USER.isBound()` 为 false。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。`ScopedValue` 在 JDK 25 是正式 API（JEP 506）。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/PoolLab.java`。用 `CyclicBarrier` 让两个父任务都开始运行后才提交子任务，使饥饿每次都出现；不加这个同步点时，是否饥饿取决于提交时序。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 饥饿的条件是「同时运行的父任务数达到池的线程数」；线程数大于并发的父任务数时不会出现，所以线上表现为高峰期偶发。
- 500ms 是观察窗口；同池场景下任务不会自行恢复，实验结束时用 `shutdownNow()` 中断。
- 没有覆盖 `ForkJoinPool`：它的 `join` 可以帮助执行其他任务，行为不同。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，全部断言通过 | 线程池一文新增两节，结论取自本次证据 |
