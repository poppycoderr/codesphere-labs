# 并行流与公共线程池

对应文章：[parallel-stream-common-pool.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/parallel-stream-common-pool.md)。

`src/ParallelLab.java` 在固定 digest 的 temurin 25 容器里以 `-XX:ActiveProcessorCount=4` 运行，公共池并行度为 3。阻塞调用用 `Thread.sleep(200)` 代替。标准输出只有确定的结论（参与的线程种类、耗时是否落在预期区间），与脚本里的预期逐行比较；原始耗时写到 `timings.log`，不参与比较。

`src/AsyncThread.java` 用 Java 8 语法写，在 temurin 8、21、25 三个版本上各用 1、2、4 个 CPU 运行：打印公共池并行度、不带执行器的 `supplyAsync` 跑在什么线程上、6 个 200 毫秒的阻塞任务几轮跑完。

类初始化的场景在子进程里运行，5 秒不结束就强制终止。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
