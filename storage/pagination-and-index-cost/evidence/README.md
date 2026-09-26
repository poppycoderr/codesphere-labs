# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 三组对照的结果 |
| `pagination-plans.txt` | 四个深度下两种分页的 `EXPLAIN ANALYZE` |
| `time-cursor-plans.txt` | 两种时间游标写法在第 50 页的 `EXPLAIN ANALYZE` |
| `container.txt` | MySQL 镜像、JDBC 驱动与 JDK 镜像 |
| `environment.txt` | 运行环境 |
