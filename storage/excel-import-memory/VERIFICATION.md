# 验证记录：百万行 Excel 的读取内存

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **文中 Fesod 的写法能编译运行**：`FesodSheet.read(File, Class, ReadListener)` 与 `PageReadListener(Consumer<List<T>>, int)` 在 `fesod-sheet 2.0.2-incubating` 中存在；按每批 1000 行读完 100 万行。
2. **流式读取的内存与行数无关**：Fesod 在 `-Xmx256m` 下读完 100 万行，堆使用峰值约 159 MB。
3. **`XSSFWorkbook` 整体加载需要数 GB**：同一个文件，`-Xmx6g` 时抛出 `OutOfMemoryError`；`-Xmx8g` 时读完，堆使用峰值约 7.5 GB。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。JDK 21.0.5，10 核 Apple Silicon、32 GB 内存；Apache Fesod 2.0.2-incubating，Apache POI 5.5.1（Fesod 依赖的版本）。

## 三、执行步骤

1. `SXSSFWorkbook` 流式写出 100 万行、10 列（订单号、用户、SKU、数量、价格、城市、状态、时间、备注等），固定随机种子；文件已存在时沿用。
2. 三次独立的 JVM：Fesod `-Xmx256m`；POI `-Xmx6g`；POI `-Xmx8g`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 堆使用峰值是每 20ms 的采样最大值，包含尚未回收的垃圾对象，不等于存活数据的大小；能确定的是「6 GB 不够、8 GB 够」这个区间。
- 所需内存与单元格内容、共享字符串数量和列数有关；换一份数据，区间会变化，量级不变。
- `-Xmx6g` 的一次运行在 OOM 前会持续 Full GC，耗时明显更长，不代表正常读取的速度。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过 | 是：「可能占用数 GB」改为实测区间 |
