# 验证记录：收集器对比

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **同一负载下三种收集器**（存活 800 MB、顺序替换，两轮）：

| 收集器 | 吞吐 | 进程 CPU | 停顿次数 | 停顿合计 | 最长停顿 | Full GC | 探针 p99 | 探针 p99.9 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| G1 | 3,332 万—3,598 万次/秒 | 2.37—2.41 核 | 284—306 | 1.31—1.44 s | 9.3—9.9 ms | 0 | 4.9—6.3 ms | 7.4—7.5 ms |
| 分代 ZGC | 4,878 万—4,988 万次/秒 | 2.64—2.68 核 | 2,045—2,174 | 17—20 ms | 0.03—0.04 ms | 0 | 0.35—0.51 ms | 0.60—1.37 ms |
| Parallel | 3,732 万—3,921 万次/秒 | 2.10 核 | 337—354 | 1.26—1.32 s | 约 50 ms | 6 | 3.0—3.2 ms | 3.7—4.0 ms |

   - ZGC 的停顿低两个数量级，单次不到 0.1 ms；这个负载下它的吞吐也最高，CPU 比 G1 多用约 0.3 核；
   - Parallel CPU 最省，老年代满了就做一次全程停顿的 Full GC（约 50 ms），20 秒 6 次；
   - G1 停顿合计与 Parallel 相当，但单次控制在 10 ms 以内，没有 Full GC。
2. **G1 各类停顿**（第一轮）：Young (Normal) 47 次、Concurrent Start 47 次、Remark 47 次、Cleanup 48 次、Prepare Mixed 48 次、Mixed 47 次，最长都在 10 ms 以内；其中 30 次带 `Evacuation Failure`，但都没有退化成 Full GC。
3. **存活数据的死亡方式决定 G1 会不会退化**：同样 800 MB、同样的替换速率，随机替换时 G1 20 秒内 13 次 Full GC（`Pause Full (G1 Compaction Pause)`，最长 75 ms），吞吐降到 2,742 万次/秒，探针 p99.9 26 ms；ZGC 在随机替换下没有 Full GC、没有分配停顿，最长停顿 0.03 ms。调试时试过把 `G1MixedGCLiveThresholdPercent` 调到 100（让更多老年代 Region 进入 Mixed 回收），Full GC 反而更多，所以没有把原因归到这个阈值上。
4. **堆余量不足时 ZGC 的退化是分配停顿**：存活 1,400 MB（堆的 70%）、顺序替换，ZGC 的停顿仍然只有 0.04 ms，但出现 62 次 `Allocation Stall`，合计 0.3 s、中位数 3.9 ms、最长 9.8 ms，吞吐从约 4,900 万降到 3,734 万次/秒；探针线程几乎不分配，p99.9 仍为 0.92 ms。同样条件下 G1 没有 Full GC。
5. **`System.gc()`**（约 400 MB 存活数据）：

| 设置 | GC 日志 | 调用期间的停顿 | 调用返回耗时 |
|---|---|---:|---:|
| G1 默认 | `Pause Full (System.gc())` | 1 次，32.8 ms | 33.4 ms |
| G1 + `-XX:+ExplicitGCInvokesConcurrent` | `Pause Young (Concurrent Start) (System.gc())` | 3 次，8.0 ms | 70.7 ms |
| G1 + `-XX:+DisableExplicitGC` | 无 | 0 | 0.0 ms |
| ZGC | `Major Collection (System.gc())` | 8 次，0.1 ms | 120.4 ms |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。temurin 21.0.12（固定 digest），容器 4 CPU、3 GB，`-Xms2g -Xmx2g`。

## 三、执行步骤

见 `scripts/verify.sh`。每次运行前 3 秒预热不计入；停顿只统计测量窗口内的 GC 日志行。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 负载是人为构造的：对象很小、分配速率极高（每秒数千万次），真实业务的对象图更复杂，吞吐排序不能直接外推。ZGC 在这里吞吐最高，只说明这个负载，不说明 ZGC 普遍吞吐更高。
- 替换方式对 G1 的影响是实测现象；原因只做推断：随机替换时每个老年代 Region 都只死一小部分，回收要复制大量仍然存活的数据，速度跟不上垃圾产生的速度。
- 断言只检查形状：两轮中 ZGC 最长停顿低于 2 ms 且不到 G1、Parallel 的五分之一，停顿合计不到 G1 的十分之一，探针 p99.9 低于 G1；顺序替换时 G1 无 Full GC、Parallel 有 Full GC；随机替换时 G1 有 Full GC、ZGC 既无 Full GC 也无分配停顿；存活 1,400 MB 时 ZGC 有分配停顿、最长停顿低于 2 ms；`System.gc()` 四种设置的日志类型。
- JDK 21 需要 `-XX:+ZGenerational` 才启用分代 ZGC；JDK 23 起默认分代。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 是：原文数字无法复现，按本次证据重写对比表；ZGC「吞吐低约 5%」在本负载下不成立；G1 退化改为「随机替换时出现 Full GC」；ZGC 分配停顿与 System.gc() 的数字更新 |
