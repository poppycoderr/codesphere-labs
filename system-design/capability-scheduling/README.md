# 异构执行器的调度：队头阻塞、瓶颈迁移与租约

对应文章：[capability-scheduling.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/capability-scheduling.md)。

40 个任务在第 0 刻同时到达，每 5 个里有 1 个需要 android（6 刻），其余需要 chrome（2 刻）。离散时间模拟比较两种调度策略（只看队头、按能力匹配），不同的执行器数量与调度器派发速率，以及执行器中途崩溃时有无租约的差别。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
