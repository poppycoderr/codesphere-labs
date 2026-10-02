# 同池等待子任务与 ThreadLocal 残留

对应文章：[thread-pool-sizing.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/thread-pool-sizing.md)。

`src/PoolLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行：

1. 2 个线程的固定线程池里提交 2 个父任务，`CyclicBarrier` 保证两个父任务都占住线程后，各自再向同一个池提交子任务并 `get()`；对照：子任务用单独的池、每任务一个虚拟线程；
2. 单线程池上，任务 A 设置 `ThreadLocal` 后不清理，任务 B 读取；对照：`finally` 里 `remove()`，以及用 `ScopedValue` 绑定。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
