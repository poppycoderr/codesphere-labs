# 百万行 Excel 的读取内存

对应文章：[mysql-bulk-import.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-bulk-import.md)（读取端一节）。写入端的对比见 [storage/mysql-bulk-import](../mysql-bulk-import/)。

`src/main/java/labs/excel/ExcelMemory.java` 先用 POI 的 `SXSSFWorkbook` 流式生成 100 万行、10 列的 xlsx（约 63 MB），再在独立的 JVM 里分别读取：

| 读取方式 | 堆上限 |
|---|---|
| Apache Fesod：`FesodSheet.read(file, OrderRow.class, new PageReadListener<>(batch -> ..., 1000)).sheet().doRead()` | 256 MB |
| POI：`new XSSFWorkbook(file)` | 6 GB、8 GB |

后台线程每 20ms 采样一次堆使用量，记录最大值。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven，以及约 8 GB 可用内存；约 4 分钟
make evidence
make clean
```
