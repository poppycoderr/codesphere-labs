# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/one-shot-delete.txt、batch-delete.txt、batch-delete-per-batch.txt` | 两种删除方式的耗时、事务数与 binlog 字节数，以及每批的行数与耗时 |
| `evidence/lock-probe-*.txt` | 三种情况的执行计划、`data_locks` 汇总与探测结果 |
| `evidence/file-size.txt、optimize-message.txt` | 删除前后与重建后的 `.ibd` 大小 |
| `evidence/range-delete.txt` | 主键区间删除的批次统计 |
| `evidence/drop-partition.txt` | 分区行数、binlog 位点与记录的语句 |
| `evidence/assertions.txt` | 断言结果 |
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容。
