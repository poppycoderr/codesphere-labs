# 验证记录：压缩对象头的实际收益

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **状态**：JDK 25.0.4 与 26.0.2 上，`UseCompactObjectHeaders` 都是正式（product）参数，默认关闭；`UseCompressedClassPointers` 默认开启，对象按 8 字节对齐。
2. **布局**：关闭时第一个 `int` 字段从 12 字节处开始，开启后从 8 字节处开始；`byte[]` 元素从 16 字节处开始，开启后从 12 字节处开始；`long[]` 两种情况都从 16 字节处开始。
3. **单个实例的大小**（JDK 25，两个 JDK 结果相同）：

| 类型 | 关闭 | 开启 | 变化 |
|---|---:|---:|---:|
| `Object` | 16 | 8 | −8 |
| `Long` | 24 | 16 | −8 |
| 两个 `int` 字段 | 24 | 16 | −8 |
| `int` + 三个引用（与 `HashMap.Node` 相同） | 32 | 24 | −8 |
| `byte[4]` | 24 | 16 | −8 |
| `Integer` | 16 | 16 | 0 |
| 一个 `int` 字段 | 16 | 16 | 0 |
| 三个 `int` 字段 | 24 | 24 | 0 |
| `byte[0]` | 16 | 16 | 0 |
| `byte[8]` | 24 | 24 | 0 |

   对象头从 12 字节变成 8 字节，但对象要按 8 字节对齐：原来补齐时浪费了至少 4 字节的对象，省下 8 字节；原来刚好对齐的对象，大小不变。
4. **100 万条目的 `HashMap<Long, Order>`**：JDK 25 上堆占用从 138.3 MB 降到 115.1 MB（−16.8%），JDK 26 上从 139.5 MB 降到 116.3 MB（−16.6%）。按类拆开（JDK 25）：`HashMap$Node` 每个 32 → 24 字节，`Long` 24 → 16，`Order` 记录 32 → 24；`String` 与它的 7 字节 `byte[]` 都是 24 → 24；8 MB 的桶数组不变。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 `evidence/jdk*-flags.txt`。temurin 25.0.4、26.0.2（固定 digest），Serial GC，堆 2 GB，压缩指针开启。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/ObjectSizes.java`。调试时先用「全量 GC 后的堆占用增量 ÷ 实例数」估算，个别类型得到 28、20 这类不可能的值，改为 `GC.class_histogram` 取实例数与字节数后结果全部是 8 的倍数；HashMap 的总量仍用堆占用增量。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 只测了对象大小与堆占用，没有测吞吐、GC 停顿与 CPU；这些取决于负载，要在自己的服务上对比。
- 省下多少取决于对象的字段组合：本例的订单结构省了约 17%，全是字符串的数据可能几乎不省。
- 只测了 Serial GC 与默认的 8 字节对齐；关闭压缩指针或改对齐方式不在本实验范围。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
