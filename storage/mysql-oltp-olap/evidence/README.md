# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/queries/<查询>/` | 两边的 SQL、结果、MySQL 每次采样耗时与 `EXPLAIN ANALYZE`、ClickHouse 的 query_log 记录 |
| `evidence/summary.md` | 中位数、范围、读取量与结果是否一致 |
| `evidence/storage.txt` | 存储占用与各列压缩大小 |
| `evidence/container.txt` | 版本、镜像与资源限制 |
| `evidence/assertions.txt` | 断言结果 |
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容。
