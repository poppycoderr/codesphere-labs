# 验证记录：收集器对比

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **同一负载下三种收集器**（JDK 25.0.4，存活 800 MB、顺序替换，两轮）：

| 收集器 | 吞吐 | 进程 CPU | 停顿次数 | 停顿合计 | 最长停顿 | Full GC | 探针 p99 | 探针 p99.9 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| G1 | 3,341 万—3,600 万次/秒 | 2.35—2.36 核 | 283—303 | 0.96—0.99 s | 6.1—7.1 ms | 0 | 3.8—4.1 ms | 5.3—5.5 ms |
| 分代 ZGC | 3,331 万—3,402 万次/秒 | 2.37—2.39 核 | 1,033—1,101 | 9—10 ms | 0.03—0.06 ms | 0 | 0.57—0.58 ms | 0.62—0.63 ms |
| Parallel | 3,691 万—3,793 万次/秒 | 2.10—2.11 核 | 333—343 | 1.39—1.48 s | 66—69 ms | 7 | 3.1—3.2 ms | 3.8—3.9 ms |

   - ZGC 的停顿低两个数量级，单次不到 0.1 ms，探针 p99.9 约 0.6 ms；吞吐比 G1 低约 5%；
   - Parallel 吞吐最高、CPU 最省，老年代满了就做一次全程停顿的 Full GC（约 67 ms），20 秒 7 次；
   - G1 停顿合计比 Parallel 少，单次控制在 10 ms 以内，没有 Full GC。
2. **G1 各类停顿**（第一轮，测量窗口内）：Young (Normal) 51 次、Concurrent Start 50 次、Remark 50 次、Cleanup 51 次、Prepare Mixed 51 次、Mixed 50 次，最长都在 7 ms 以内；其中 58 次带 `Evacuation Failure: Allocation`，但都没有退化成 Full GC。
3. **MXBean 的统计口径**（进程生命周期累计）：G1 的 `G1 Young Generation` 259 次、`G1 Concurrent GC` 114 次（Remark 与 Cleanup 记在这里）、`G1 Old Generation` 0 次；ZGC 的 `ZGC Minor Cycles` 423 次、累计 7,577 ms，`ZGC Minor Pauses` 1,274 次、8 ms：`Cycles` 是并发周期的时长，应用照常运行，真正的停顿在 `Pauses` 下。
4. **存活数据的死亡方式决定 G1 会不会退化**：同样 800 MB、同样的替换速率，随机替换时 G1 20 秒内 12 次 Full GC（`Pause Full (G1 Compaction Pause)`，最长 80 ms），吞吐降到 2,671 万次/秒，探针 p99.9 21 ms；ZGC 在随机替换下没有 Full GC、没有分配停顿，最长停顿 0.03 ms。调试时（JDK 21）试过把 `G1MixedGCLiveThresholdPercent` 调到 100（让更多老年代 Region 进入 Mixed 回收），Full GC 反而更多，所以没有把原因归到这个阈值上。
5. **堆余量不足时 ZGC 的退化是分配停顿**：存活 1,700 MB（堆的 83%）、顺序替换，ZGC 的停顿仍然只有 0.05 ms，但出现 777 次 `Allocation Stall`，合计 1.5 s、中位数 1.5 ms、最长 9.0 ms，吞吐降到 3,023 万次/秒，CPU 升到 3.24 核；探针线程几乎不分配，p99.9 为 1.9 ms。同样条件下 G1 没有 Full GC（停顿 2,432 次、合计 3.9 s，最长 8.7 ms）。存活 1,400 MB 时（调试运行），JDK 25 的 ZGC 没有分配停顿，JDK 21 有。
6. **`System.gc()`**（约 400 MB 存活数据）：

| 设置 | GC 日志 | 调用期间的停顿 | 调用返回耗时 |
|---|---|---:|---:|
| G1 默认 | `Pause Full (System.gc())` | 1 次，32.6 ms | 32.8 ms |
| G1 + `-XX:+ExplicitGCInvokesConcurrent` | `Pause Young (Concurrent Start) (System.gc())` | 3 次，9.8 ms | 76.4 ms |
| G1 + `-XX:+DisableExplicitGC` | 无 | 0 | 0.0 ms |
| ZGC | `Major Collection (System.gc())` | 8 次，0.1 ms | 108.5 ms |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。temurin 25.0.4（固定 digest，见 `evidence/java-version.txt`），容器 4 CPU、3 GB，`-Xms2g -Xmx2g`。

## 三、执行步骤

见 `scripts/verify.sh`。每次运行前 3 秒预热不计入；停顿只统计测量窗口内的 GC 日志行。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 负载是人为构造的：对象很小、分配速率极高（每秒数千万次），真实业务的对象图更复杂，吞吐排序不能直接外推。调试时在 JDK 21 上用 `-XX:+ZGenerational` 跑过同样的负载，ZGC 吞吐反而最高，说明吞吐的相对关系对 JDK 版本和负载都敏感。
- 替换方式对 G1 的影响是实测现象；原因只做推断：随机替换时每个老年代 Region 都只死一小部分，回收要复制大量仍然存活的数据，速度跟不上垃圾产生的速度。
- 断言只检查形状：两轮中 ZGC 最长停顿低于 2 ms 且不到 G1、Parallel 的五分之一，停顿合计不到 G1 的十分之一，探针 p99.9 低于 G1；顺序替换时 G1 无 Full GC、Parallel 有 Full GC；随机替换时 G1 有 Full GC、ZGC 既无 Full GC 也无分配停顿；存活 1,700 MB 时 ZGC 有分配停顿、最长停顿低于 2 ms；`System.gc()` 四种设置的日志类型。
- JDK 25 的 ZGC 只有分代模式；JDK 21 需要 `-XX:+ZGenerational` 才启用分代 ZGC。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立（JDK 25.0.4），全部断言通过 | 是：原文数字无法复现，按本次证据重写对比表；G1 退化改为「随机替换时出现 Full GC」；ZGC 分配停顿改为存活 1,700 MB 时出现；System.gc() 的数字更新 |
