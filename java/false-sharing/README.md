# 伪共享

对应文章：[false-sharing.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/false-sharing.md)。

`src/FalseSharingLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。两个线程各自对自己的变量做 1 亿次原子自增，比较变量之间距离不同时每次操作的耗时：

1. 同一个对象里相邻的两个 `volatile long`；
2. 用继承层次手工填充，两侧各 56 字节与各 120 字节；
3. `@Contended`（需要 `-XX:-RestrictContended`），以及不加这个开关时的字段布局；
4. `AtomicLongArray` 的相邻元素与相隔 32 个元素；16 个起始位置上相距 64 字节、128 字节的元素对；
5. 一个线程只读、另一个线程写它旁边的字段；
6. 普通（非 `volatile`）字段的自增循环。

这是计时类实验：脚本只断言倍数范围（例如相邻字段比 `@Contended` 慢 3 倍以上），不断言具体的纳秒数。运行时需要至少 2 个空闲核，不要同时跑其他负载。

## 快速运行

```bash
make verify     # 需要 Docker；约 3 分钟
make evidence
make clean
```
