# 验证记录：伪共享

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.4，Docker Desktop 虚拟机 6 核（宿主机 Apple M1 Max）。每个线程做 1 亿次 `getAndAdd`，数值是每次操作的纳秒数；两线程场景取 5 轮的中位数，单线程基准取最快的一轮。

| 场景 | 字段偏移 | 每次操作 |
|---|---|---|
| 单线程写一个字段 | — | 6.94 ns |
| 两线程写相邻的两个 `volatile long` | 16、24 | 41.06 ns |
| 手工填充，两侧各 56 字节 | 72、136 | 11.13 ns |
| 手工填充，两侧各 120 字节 | 136、264 | 7.29 ns |
| `@Contended`（`-XX:-RestrictContended`） | 144、280 | 7.20 ns |
| `AtomicLongArray` 相邻元素 | — | 62.71 ns |
| `AtomicLongArray` 相隔 32 个元素 | — | 7.13 ns |

1. 相邻字段是 `@Contended` 的 5.70 倍、单线程的 5.92 倍。
2. 不加 `-XX:-RestrictContended` 时，应用类上的 `@Contended` 不生效，两个字段的偏移仍是 16 与 24。`ContendedPaddingWidth` 默认 128。
3. 16 个起始位置上，相距 64 字节的元素对有 0 对互相拖慢，相距 128 字节的也是 0 对。但两侧各 56 字节的手工填充（两个字段相距 64 字节）是 11.13 ns，比 120 字节填充与 `@Contended` 慢约一半。
4. 只读线程：单独读 0.57 ns；旁边的字段被另一个线程写时 1.94 ns；写者的字段用 `@Contended` 隔开时 0.57 ns。
5. 普通（非 `volatile`）字段的自增循环每次不到 0.0001 ns，两个字段最终值都正确：循环被编译器合并成一次加法，没有逐次写内存。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/cache-line.txt`](evidence/cache-line.txt)。容器内 `getconf LEVEL1_DCACHE_LINESIZE` 报告 64，宿主机 `sysctl hw.cachelinesize` 报告 128。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/FalseSharingLab.java`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 这是计时实验，倍数随运行波动：建立实验时的几次运行里，相邻字段与隔开字段的比值在约 4 到 10 倍之间。脚本只断言「3 倍以上」这类范围，归档的是其中一次的数字。
- 运行在虚拟机里，线程没有绑核，两个线程落在哪两个核上由调度器决定；物理机、不同的 CPU 型号、同一个核的两个超线程上，倍数都会不同。
- 每次操作是一个原子自增，循环里没有别的工作。真实业务代码里写共享变量只占一小部分时间，伪共享造成的整体差异会小得多；本实验说明的是机制和上限，不是某个应用能得到的收益。
- 56 字节填充比 120 字节填充慢的原因没有在本实验中确认（可能与相邻缓存行预取有关），只记录现象。
- 没有用硬件性能计数器（缓存行争用事件）做直接观测：虚拟机里不可用。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-05 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
