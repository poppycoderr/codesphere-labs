# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 断言用的结论行 |
| `timings.tsv` | 每个场景的结果、连接算子、先读的表（哈希连接为构建侧）、内层循环次数与耗时中位数（毫秒） |
| `plans.txt` | 每条 SQL 最后一次 EXPLAIN ANALYZE 的完整输出 |
| `join-buffer.tsv` | join_buffer_size 与 Created_tmp_files |
| `mysql-version.txt` | MySQL 版本 |
| `environment.txt` | 运行环境 |
