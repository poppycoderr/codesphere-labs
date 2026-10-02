# 导入的数据合同

对应文章：[mysql-bulk-import.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-bulk-import.md) 第六节。

`schema/run.sql` 用一份 7 行的合成文件（2 行合格，5 行各有一种问题）在 MySQL 8.4.11 上依次执行：

1. 用 `INSERT IGNORE` 直接写入带类型的目标表，看每个问题行最后存成了什么；
2. 先原样写入全字符串的暂存表，按规则分类：不合格的进拒绝表并写明原因，合格的写入目标表；核对「原始 = 写入 + 拒绝」；
3. 重跑同一批；
4. 第 2 批是来源的全量快照，其中少了一行：只做 upsert 与按批次号标记缺失行；
5. 迟到的旧版本：无条件覆盖与按来源版本条件写入。

期望值在 `schema/expected.tsv`，由数据手工推出。

## 快速运行

```bash
make verify     # 需要 Docker；约 40 秒
make evidence
make clean
```
