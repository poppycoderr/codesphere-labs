# count(*)、count(1) 与 count(列)

对应文章：[count-semantics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/count-semantics.md)。

`schema/setup.sql` 造 50 万行订单（`coupon_id` 可为 NULL 且有索引，`remark` 可为 NULL 且无索引）；`scripts/verify.sh` 对五种 COUNT 写法分别取结果、`EXPLAIN FORMAT=JSON` 中的索引，以及 `EXPLAIN ANALYZE` 的实际耗时（6 次去掉第一次后取中位数），再比较 `information_schema.TABLES.TABLE_ROWS` 与真实行数。

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence   # 重新采集 evidence/
make clean
```
