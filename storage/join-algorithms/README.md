# JOIN 的执行方式

对应文章：[join-algorithms.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/join-algorithms.md)。

`schema/setup.sql` 造 2 万客户、30 万订单：`orders.customer_id` 有索引，`orders.customer_ref` 存同样的值但没有索引，`customers.code` 与 `id` 相同但没有索引。`scripts/verify.sh` 用六条 SQL 分别得到索引嵌套循环、索引只在另一侧、哈希连接、`NO_BNL` 关闭哈希连接、全量连接的两种算法，从 `EXPLAIN ANALYZE` 读连接算子、驱动表、内层循环次数与顶层实际耗时（5 次中位数；关闭哈希连接的那条只跑 1 次）；再在三种 `join_buffer_size` 下执行全量哈希连接，读 `Created_tmp_files`。

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence   # 重新采集 evidence/
make clean
```
