# volatile、CAS 与 LongAdder

对应文章：[volatile-cas-and-longadder.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/volatile-cas-and-longadder.md)。

单文件程序 `src/Atomics.java`，六组对照：

1. 8 个线程各对 `volatile long` 做 100 万次 `++`，与 `AtomicLong` 对比；
2. `AtomicReference` 上的 CAS 循环：计算函数和放在循环里的「副作用」各执行了多少次；
3. 无锁栈复用节点时的 ABA：按固定交错顺序重放，`AtomicReference` 与 `AtomicStampedReference` 对比；
4. `synchronized`、`AtomicLong`、`LongAdder` 在 1、2、4、8 个线程下的计数吞吐；
5. 限额 1000：`LongAdder` 先 `sum()` 再 `increment()`，与 `AtomicLong` 的条件 CAS 对比；
6. `VarHandle.compareAndSet` 的基本用法。

## 快速运行

```bash
make verify     # 需要 JDK 21；约 20 秒，占满 8 个线程
make evidence
make clean
```
