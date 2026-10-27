# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 断言用的结论行 |
| `metadata-locks.tsv` | 等待期间 orders 上的元数据锁：线程、锁类型、状态、语句 |
| `processlist.tsv` | 等待期间各会话的状态 |
| `lock-waits.tsv` | sys.schema_table_lock_waits 的输出 |
| `s1.txt、s2.txt、s3.txt` | 三个会话的输出，s2/s3 含实际等待秒数 |
| `timeout.txt` | 超时场景的报错与等待秒数 |
| `mysql-version.txt` | MySQL 版本 |
| `environment.txt` | 运行环境 |
