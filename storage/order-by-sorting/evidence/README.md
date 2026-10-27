# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 断言用的结论行 |
| `index-direction.tsv` | 四种排序方向的索引、Extra、读取次数与前 10 个 id |
| `sort-buffer.tsv` | 缓冲区大小、LIMIT、是否优先队列、溢出块数、合并趟数、峰值内存 |
| `details.txt` | 完整的 filesort_summary 与同值分页的 id |
| `mysql-version.txt` | MySQL 版本 |
| `environment.txt` | 运行环境 |
