# 验证记录：GC 基线

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **默认堆参数跟着容器内存走**：容器内存 2 GB 时，`MaxHeapSize` 512 MB（1/4）、`InitialHeapSize` 32 MB（1/64）、`G1HeapRegionSize` 1 MB、`MaxGCPauseMillis` 200；4 GB 时最大堆 1 GB。`-XX:MaxRAMPercentage=70.0` 在 2 GB 容器里得到约 1.4 GB 的最大堆。
2. **堆变大，GC 次数少得多，单次暂停变长，合计停顿更少**（3 次中位数，每次 8 秒，分配约 2.5 GB/s）：

| 参数 | Young GC 次数 | 平均暂停 | 最长暂停 | 合计 | 并发标记周期 | Full GC |
|---|---:|---:|---:|---:|---:|---:|
| `-Xmx512m` | 517 | 2.07 ms | 4.07 ms | 1,071 ms | 86 | 0 |
| `-Xmx2g` | 39 | 6.99 ms | 13.82 ms | 265 ms | 4 | 0 |
| `-Xmx512m -XX:MaxGCPauseMillis=50` | 504 | 2.19 ms | 4.89 ms | 1,090 ms | 86 | 0 |

   暂停目标从 200 ms 降到 50 ms 没有可见效果：实际暂停本来就远低于目标，G1 没有理由调整年轻代。没有 Full GC，但 512 MB 堆下 8 秒跑了 86 个并发标记周期。
3. **`jstat -gcutil` 的 `CGC` 列统计的是并发周期里的暂停（Remark 与 Cleanup），不是周期数**：GC 日志里每个周期各有一次 Remark 和一次 Cleanup。
4. **OOM 转储的大小取决于存活对象，不是堆的上限**：256 MB 堆，保留 64 KB 数组时转储 244 MB；保留 1 MB 数组时只有 133 MB。1 MB 的数组在 1 MB 的 region 里是大对象，每个要占两个 region，堆满时存活数据只有一半左右。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。temurin 21.0.12（固定 digest），容器 2 CPU，内存 2 GB 或 4 GB。

## 三、执行步骤

见 `scripts/verify.sh`：默认值、三种 GC 配置各 3 次、运行中取 `jcmd` 与 `jstat`、两种 OOM 转储。GC 统计由 `scripts/summarize.py` 从 `-Xlog:gc` 日志得出。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 2 CPU 的容器里单次测量波动大：调试时见过一次 512 MB 堆平均暂停 7.5 ms 的运行，所以取 3 次中位数，并只断言稳定的关系：2 GB 堆的 GC 次数不到 512 MB 堆的五分之一，合计停顿更少；三种配置都没有 Full GC。
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
| 2026-09-28 | 首次建立，全部断言通过 | 是：默认值改为容器内存下的结果（原文「16GB 内存」与 512 MB/8 GB 的数值不符）；GC 表格按本次中位数重写，暂停目标 50 ms 无可见效果；CGC 的含义与 OOM 转储大小的说法修正 |
