# 验证记录：volatile、CAS 与 LongAdder

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **volatile 不保证复合操作原子**：8 个线程各对 `volatile long` 做 100 万次 `++`，3 轮都丢失约 634 万—648 万次更新；同样的循环里 `AtomicLong` 每轮都是 8,000,000。
2. **CAS 失败会重试，计算函数可能执行多次**：8 个线程各做 10 万次 `AtomicReference` 更新，成功 80 万次，计算函数执行约 462 万次（5.78 倍）。放在重试循环里的「副作用」执行次数与计算函数相同，最终状态仍然正确。
3. **ABA 在节点复用时有害**：按「线程 1 读到栈顶 A、准备换成 B；线程 2 弹出 A 和 B，再把 A 压回」的固定顺序重放：
   - `AtomicReference`：线程 1 的 CAS 成功，栈变成 `[B, C]`，已经交给别人的 B 回到了栈顶；
   - `AtomicStampedReference`：版本号已从 0 变成 3，CAS 失败，栈仍是 `[A, C]`。
4. **高竞争计数用 LongAdder**（3 轮取最好）：
   - 8 个线程：`LongAdder` 约 10.6 亿次/秒，`AtomicLong` 约 2064 万次/秒，`synchronized` 约 2959 万次/秒；
   - `AtomicLong` 从 1 个线程的约 1.46 亿次/秒降到 8 个线程的约 2064 万次/秒，线程越多总吞吐越低；
   - 1 个线程时 `synchronized` 约 4.26 亿次/秒，高于 `AtomicLong`：无竞争的循环里，JIT 可以做锁粗化等优化，这个数字不能说明有竞争时的表现。
5. **LongAdder 没有条件更新**：限额 1000，8 个线程各尝试 1 万次，`LongAdder` 用「`sum()` 小于 1000 就 `increment()`」3 轮最终为 1000、1005、1003；`AtomicLong.getAndUpdate` 的条件 CAS 每轮都是 1000。超发来自「先检查再递增」不是原子操作。
6. 8 个线程对同一字段执行 `VarHandle.compareAndSet(0 → 1)`，只有 1 个成功。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。JDK 21.0.5，10 核 Apple Silicon。

## 三、执行步骤

`java src/Atomics.java` 依次运行六组对照。吞吐测试每种计数器在每个线程数下运行 3 轮、每轮 500ms，取最好的一轮换算成每秒次数；内层每次连续递增 1000 次再检查停止标志。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.tsv` | 六组对照的结果 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 吞吐不是 JMH 基准，没有隔离 JIT、GC 和 CPU 频率的影响；断言只要求 8 个线程时 `LongAdder` 超过 `AtomicLong` 的 5 倍、`AtomicLong` 在 8 个线程时低于单线程。数字只用于同一次运行内的相对比较。
- ABA 实验在一个线程里按固定顺序重放交错，用来确定性地展示结果，不是真实的并发竞态。
- 丢失的更新次数与 CPU 核数、调度有关，只说明「会丢」，不说明丢多少。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-25 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
