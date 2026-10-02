# 时间边界与窗口函数的语义

对应文章：[sql-time-and-window.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/sql-time-and-window.md)。

- `schema/setup.sql`：七张小表的合成数据，每张对应一个问题；
- `schema/queries.sql`：26 条查询，每条输出「键、结果」；
- `schema/expected.tsv`：每个键的期望值与理由，按数据手算。

`scripts/verify.sh` 在 labs 共享的 MySQL 8.4.11 容器里执行查询，逐行与期望值比较，并保存两种时间范围写法的执行计划。

## 快速运行

```bash
make verify     # 需要 Docker；约 40 秒
make evidence
make clean
```
