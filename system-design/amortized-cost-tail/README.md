# 均摊代价与尾延迟

对应文章：[amortized-cost-and-tail-latency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/amortized-cost-and-tail-latency.md)。

单文件程序 `src/Amortized.java`，按参数运行不同场景：

1. `list`：向默认容量与预分配容量的 `ArrayList` 各加入 1,000 万个元素，逐次记录 `add` 耗时，看最慢的 10 次是否落在扩容点；
2. `map`：向默认容量与 `HashMap.newHashMap(n)` 预分配的 `HashMap` 各放入 200 万个键，同样逐次记录 `put` 耗时；
3. `lookup` / `lookup-skewed` / `lookup-int`：N 从 1 到 256，比较顺序扫描数组与 `HashMap.get` 的平均耗时；字符串键分均匀查询与 90% 命中同一个热键两种分布，另有一组 `int` 键对照。

前两个场景用 Epsilon GC（不回收）和 `-XX:+AlwaysPreTouch` 运行，排除 GC 与缺页，只留下复制本身的代价。

## 快速运行

```bash
make verify     # 需要 JDK 21；约 1 分钟，堆 6 GB
make evidence
make clean
```
