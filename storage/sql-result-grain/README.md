# JOIN 之后的结果粒度

对应文章：[sql-result-grain.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/sql-result-grain.md)。

- `schema/setup.sql`：合成数据。5 个用户、5 笔订单（1 笔游客订单的 `user_id` 为 NULL）、8 行明细、5 笔支付；数据量小到可以手算。
- `schema/queries.sql`：30 条查询，每条输出「键、结果」。
- `schema/expected.tsv`：每个键的期望值和手算算式。期望值按数据手算，不由另一条 SQL 得出。

`scripts/verify.sh` 在 labs 共享的 MySQL 8.4.11 容器里执行查询，逐行与期望值比较。

## 快速运行

```bash
make verify     # 需要 Docker；约 40 秒
make evidence
make clean
```
