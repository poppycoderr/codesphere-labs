# 虚拟线程的收益、上限与 pinning

对应文章：[virtual-threads.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/virtual-threads.md)。

单文件程序 `src/VT.java`，两组对照：

1. `throughput`：每个任务 `Thread.sleep(50)`，5000 个任务分别交给 200 线程的平台线程池和虚拟线程；再用 20 个许可的信号量模拟连接池，让 1000 个虚拟线程排队。
2. `pinning`：1000 个虚拟线程各自进入一个独立的 `synchronized` 块（或 `ReentrantLock`），在里面阻塞 50ms。分别在本机 JDK 21.0.5，以及 Docker 中限制 6 核的 JDK 21.0.12、25.0.4 上运行。

## 快速运行

```bash
make verify     # 需要 JDK 21 与 Docker；约 1 分钟
make evidence
make clean
```
