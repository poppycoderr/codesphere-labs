# GC 基线

对应文章：[jvm-troubleshooting.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/jvm-troubleshooting.md)。

全部在固定 digest 的 temurin 21 容器（2 CPU）里运行：

1. 容器内存 2 GB、4 GB 时 `-XX:+PrintFlagsFinal` 的默认堆参数，以及 `-XX:MaxRAMPercentage=70.0` 的效果；
2. `src/Alloc.java`：约 2.5 GB/s 的分配速率，1% 的对象放进固定大小的环形缓冲区长期存活。分别用 512 MB 堆、2 GB 堆、512 MB 堆加 `-XX:MaxGCPauseMillis=50` 各跑 3 次、每次 8 秒，从 GC 日志统计暂停；
3. 负载运行中执行 `jcmd GC.heap_info` 与 `jstat -gcutil`；
4. `src/Oom.java`：256 MB 堆里不断保留 64 KB 或 1 MB 的数组直到 OOM，记录 `-XX:+HeapDumpOnOutOfMemoryError` 生成的文件大小（文件本身不归档）。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 2 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
