# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 各读法的耗时与读取行数、结果摘要、写路径耗时、异步投影的各阶段 |
| `query-plans.txt` | GROUP BY 与同步读模型查询的 `EXPLAIN ANALYZE` |
| `container.txt` | MySQL 镜像、JDBC 驱动与 JDK 镜像 |
| `environment.txt` | 运行环境 |
