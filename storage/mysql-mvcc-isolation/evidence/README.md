# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/timeline-rr.txt、timeline-rc.txt` | 两种隔离级别下 T2、T4、T5 的读取结果 |
| `evidence/snapshot-begin.txt、snapshot-consistent.txt` | 快照建立时机 |
| `evidence/snapshot-vs-current.txt` | 快照读与当前读混用 |
| `evidence/phantom-*.txt` | 幻读的三种读法与插入探测 |
| `evidence/semi-consistent-*.txt` | 无索引 UPDATE 在 RR 与 RC 下的探测结果 |
| `evidence/long-transaction.txt` | 长事务持有期间与提交后的 History list length |
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容。
