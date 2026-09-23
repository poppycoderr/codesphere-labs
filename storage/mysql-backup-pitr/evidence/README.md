# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/timeline.tsv` | 备份、写入、误删、发现与恢复各阶段的 UTC 时间 |
| `evidence/backup-manifest.tsv、backup-stderr.txt` | 备份命令、退出码、大小、SHA-256、GTID_PURGED 与 binlog 起点 |
| `evidence/binlog-list.tsv、binlog-archive-manifest.tsv、orders-per-binlog.tsv` | 归档的 binlog 列表、SHA-256 与每个文件中的订单数 |
| `evidence/bad-delete.tsv、bad-transaction.tsv、markers-around-incident.tsv` | 误删的提交时间、行数、GTID 与前后订单的提交时间 |
| `evidence/*-checks.tsv` | 各阶段的业务校验和与不变量 |
| `evidence/restore-*-gtid.txt、source-gtid-executed.txt` | 恢复各阶段与 source 的 GTID 集合 |
| `evidence/failure-*.tsv` | 两个失败路径的结果 |
| `evidence/restore-smoke.tsv` | 只读冒烟查询 |
| `evidence/assertions.txt` | 断言结果 |
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容，GTID 中的 server_uuid 每次运行都会变化。
