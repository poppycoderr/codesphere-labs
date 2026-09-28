# 压缩对象头的实际收益

对应文章：[compact-object-headers.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/compact-object-headers.md)。

单文件程序 `src/ObjectSizes.java`，分别在固定 digest 的 temurin 25.0.4 与 26.0.2 上、以 `-XX:-UseCompactObjectHeaders` 与 `-XX:+UseCompactObjectHeaders` 运行（Serial GC，堆 2 GB），共 4 次：

1. 用 `Unsafe` 读取第一个字段与数组元素的起始偏移；
2. 每种类型分配 100 万个实例，用 `GC.class_histogram`（与 `jcmd` 相同的数据）取新增实例数与字节数，算出单个实例的大小；
3. 建一个 100 万条目的 `HashMap<Long, Order>`，记录全量 GC 后的堆占用增量，并按类拆开。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 2 分钟
make evidence
make clean
```
