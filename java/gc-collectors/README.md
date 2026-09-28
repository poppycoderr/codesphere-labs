# 收集器对比：G1、分代 ZGC、Parallel

对应文章：[g1-and-zgc.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/g1-and-zgc.md)。

全部在固定 digest 的 temurin 25.0.4 容器（4 CPU、3 GB，堆固定 2 GB）里运行：

1. `src/Workload.java`：一块长期存活数据（每块约 1 KB），两个业务线程高速分配 64—512 字节的短命数组，每 200 次操作替换一块长期存活数据；一个探针线程每次睡 1 ms，记录实际多等了多久。预热 3 秒、测量 20 秒。
   - 主对比：存活 800 MB、按先后顺序替换（最老的先死，像滑动窗口或按时间淘汰的缓存），G1、分代 ZGC、Parallel 各跑两轮；程序结束时输出各个 `GarbageCollectorMXBean` 的累计值；
   - 退化：存活 800 MB 改为随机替换（G1、ZGC）；存活 1,700 MB、顺序替换（G1、ZGC）。
2. `src/ExplicitGc.java`：约 400 MB 存活数据，调用一次 `System.gc()`，比较 G1 默认、`-XX:+ExplicitGCInvokesConcurrent`、`-XX:+DisableExplicitGC` 与 ZGC。

停顿从 `-Xlog:gc,gc+phases` 日志统计（`scripts/summarize.py`），只算测量窗口内的 `Pause` 行；ZGC 的分配停顿取 `Allocation Stall` 行。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 5 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
