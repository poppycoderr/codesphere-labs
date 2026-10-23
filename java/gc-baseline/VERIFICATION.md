# 验证记录：GC 基线

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **默认堆参数跟着容器内存走**（JDK 25，与 JDK 21 的旧证据一致）：容器内存 2 GB 时，`MaxHeapSize` 512 MB（1/4）、`InitialHeapSize` 32 MB（1/64）、`G1HeapRegionSize` 1 MB、`MaxGCPauseMillis` 200；4 GB 时最大堆 1 GB。`-XX:MaxRAMPercentage=70.0` 在 2 GB 容器里得到约 1.4 GB 的最大堆。
2. **堆变大，GC 次数少得多，单次暂停变长，合计停顿更少**（每次 8 秒，分配约 2.5 GB/s；Full GC 列是 3 次各自的次数，其余取中位数）。JDK 21 与 25 走向一致：

| 参数 | JDK | Young GC 次数 | 平均暂停 | 最长暂停 | Young GC 合计 | 并发标记周期 | Full GC |
|---|---|---:|---:|---:|---:|---:|---:|
| `-Xmx512m` | 25 | 510 | 2.14 ms | 13.14 ms | 1,120 ms | 84 | 1 / 0 / 0 |
| `-Xmx2g` | 25 | 24 | 9.08 ms | 36.18 ms | 236 ms | 1 | 0 / 0 / 0 |
| `-Xmx512m -XX:MaxGCPauseMillis=50` | 25 | 502 | 2.25 ms | 30.41 ms | 1,092 ms | 82 | 37 / 0 / 0 |
| `-Xmx512m` | 21 | 491 | 2.32 ms | 7.64 ms | 1,141 ms | 80 | 0 / 1 / 0 |
| `-Xmx2g` | 21 | 40 | 8.63 ms | 19.18 ms | 345 ms | 4 | 0 / 0 / 0 |
| `-Xmx512m -XX:MaxGCPauseMillis=50` | 21 | 503 | 2.23 ms | 6.04 ms | 1,110 ms | 81 | 0 / 0 / 0 |

   暂停目标从 200 ms 降到 50 ms 对 Young GC 没有可见效果：实际暂停本来就远低于目标，G1 没有理由调整年轻代。
3. **512 MB 堆处在临界点**：长期存活约 200 MB，Young GC 频繁出现疏散失败（JDK 21 日志为 `(Evacuation Failure)`，JDK 25 为 `(Evacuation Failure: Allocation)`），偶尔升级为 Full GC（`G1 Compaction Pause`，每次约 30 ms，压缩到约 202 MB）。两个版本都出现过，次数在 0 到 37 之间随机分布，与暂停目标无关；37 次的那一轮 Full GC 合计约 1.1 秒。2 GB 堆在两个版本的 6 次运行里都没有 Full GC。
4. **`jstat -gcutil` 的 `CGC` 列统计的是并发周期里的暂停（Remark 与 Cleanup），不是周期数**：GC 日志里每个周期各有一次 Remark 和一次 Cleanup。
5. **OOM 转储的大小取决于存活对象，不是堆的上限**：256 MB 堆，保留 64 KB 数组时转储 244 MB；保留 1 MB 数组时只有 134 MB（JDK 25）。1 MB 的数组在 1 MB 的 region 里是大对象，每个要占两个 region，堆满时存活数据只有一半左右。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。temurin 25.0.4 与 21.0.12（均固定 digest，版本见 `evidence/jdk-versions.txt`），容器 2 CPU，内存 2 GB 或 4 GB。默认值、`jcmd`、`jstat` 与 OOM 只在 JDK 25 上运行，GC 配置两个版本各跑一组。

## 三、执行步骤

见 `scripts/verify.sh`：默认值、三种 GC 配置在两个 JDK 上各 3 次、运行中取 `jcmd` 与 `jstat`、两种 OOM 转储。GC 统计由 `scripts/summarize.py` 从 `-Xlog:gc` 日志得出。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 2 CPU 的容器里单次测量波动大：调试时见过一次 512 MB 堆平均暂停 7.5 ms 的运行，所以取 3 次中位数，并只断言稳定的关系：两个版本上 2 GB 堆的 Young GC 次数都不到 512 MB 堆的五分之一，且 2 GB 堆没有 Full GC。512 MB 堆是否出现 Full GC 是随机的，不作断言；2026-10-23 调试时两个版本的 512 MB 配置都出现过十几到三十几次 Full GC 的运行。
- 两个版本 3 次运行的样本太少，不能据此判断哪个版本更容易发生 Full GC。
- 负载是人为构造的（大量 64—256 字节的短命数组、1% 进入固定大小的缓冲区），真实应用的存活对象分布不同，数字只说明趋势。
- 只测了 G1；其他收集器见 `java/gc-collectors`。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-23 | 改为 temurin 25.0.4，并在 21.0.12 上跑 GC 对照；默认值不变，`jcmd GC.heap_info` 的输出格式改为 `total reserved …, committed …`，断言随之更新；去掉「三种配置都没有 Full GC」的断言（两个版本的 512 MB 堆都出现了 Full GC） | 是：数字改为 JDK 25 的结果，表格加 JDK 21 对照，第二节第 3 条改为 512 MB 堆的临界点与偶发 Full GC，日志关键字和 ZGC 说明更新到 JDK 25 |
| 2026-09-28 | 首次建立，全部断言通过 | 是：默认值改为容器内存下的结果（原文「16GB 内存」与 512 MB/8 GB 的数值不符）；GC 表格按本次中位数重写，暂停目标 50 ms 无可见效果；CGC 的含义与 OOM 转储大小的说法修正 |
