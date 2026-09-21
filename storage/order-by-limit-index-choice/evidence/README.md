# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成，未手工修改。

| 文件 | 内容 |
|---|---|
| `summary.md`、`summary.csv` | 每条查询的索引、读取次数、耗时统计、缓冲池计数、结果校验和 |
| `assertions.txt` | `summarize.py` 的断言结果 |
| `dataset.txt` | 行数与按 state、event_type、deleted 分组的分布 |
| `database-status.txt` | MySQL 版本、缓冲池大小、隔离级别、表大小、索引基数 |
| `container.txt` | 镜像 digest 与资源限制 |
| `environment.txt` | 宿主机系统、CPU 数、Docker 版本 |
| `<分组>/<查询>/query.sql` | 执行的 SQL |
| `<分组>/<查询>/explain-analyze.txt` | 7 次 `EXPLAIN ANALYZE` 的完整输出 |
| `<分组>/<查询>/explain.json` | `EXPLAIN FORMAT=JSON`（估算，不执行） |
| `<分组>/<查询>/handler-status.txt` | 执行一次查询后的会话级 `Handler_read%` |
| `<分组>/<查询>/buffer-pool.txt` | 同一次执行前后全局 `Innodb_buffer_pool_read_requests`、`Innodb_buffer_pool_reads` 的差值 |
| `<分组>/<查询>/result-ids.txt` | 返回的 100 个 id，用于比较各写法结果是否一致 |

分组：`before` 为原查询，`after` 为修复写法，`control` 为对照查询。

统计口径：

- 耗时取每次 `EXPLAIN ANALYZE` 根节点 `actual time=a..b` 中的 `b`，即返回最后一行的时间，单位毫秒；包含 `EXPLAIN ANALYZE` 自身的计时开销，不含客户端网络与结果传输。
- 每条查询先执行 3 次预热，再采样 7 次，汇总表给出中位数、最小值和最大值。
- 缓冲池计数是全局状态的差值，实验期间容器内没有其他负载。
