# 验证记录：虚拟线程的收益、上限与 pinning

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **收益**：5000 个阻塞 50ms 的任务，200 线程的平台线程池用 1,376ms（理论 1,250ms），虚拟线程用 67ms（理论约 50ms）。
2. **上限**：1000 个虚拟线程共用 20 个「连接」（信号量），用 2,756ms（理论 2,500ms），耗时由连接数决定。
3. **pinning**：1000 个虚拟线程各在一个独立的锁里阻塞 50ms，没有锁竞争：

| 环境 | `synchronized` | `ReentrantLock` |
|---|---:|---:|
| 本机 JDK 21.0.5，10 核 | 5,426ms | 60ms |
| Docker JDK 21.0.12，6 核 | 8,783ms | 63ms |
| Docker JDK 25.0.4，6 核 | 66ms | 63ms |

JDK 21 上 `synchronized` 的耗时约等于 1000 ÷ 核数 × 50ms：被 pin 住的虚拟线程独占载体线程，并发度退化成核数。JDK 25 上两者相同（JEP 491，JDK 24 起）。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。本机 JDK 21.0.5，10 核 Apple Silicon；Docker 镜像按 digest 固定，`--cpus 6` 时 `availableProcessors` 为 6。

## 三、执行步骤

`scripts/verify.sh` 先在本机运行 `throughput` 与 `pinning`，再在两个 Docker 镜像中运行 `pinning`。`throughput` 先用 1000 个虚拟线程任务预热一次。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 耗时受机器负载影响。断言只检查相对关系：平台线程池不低于理论值且至少是虚拟线程的 5 倍；20 个连接时在 2,500—4,000ms 之间；被 pin 住时不低于「1000 ÷ 核数 × 50ms」，不 pin 时低于 250ms。
- `Thread.sleep` 模拟的是阻塞等待，不包含真实 I/O 的 CPU 开销；不能据此推出真实服务的吞吐提升倍数。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过 | 文章数字来自更早的一次运行（1,487/70/2,886ms，8,766/62ms），改为本次证据的数字 |
