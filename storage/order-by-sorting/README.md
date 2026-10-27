# ORDER BY：索引方向、排序溢出与同值分页

对应文章：[order-by-sorting.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/order-by-sorting.md)。

`schema/setup.sql` 造 20 万行商品，带升序索引 `(category, score)` 与 `(category, score DESC)`；`scripts/verify.sh` 分三组：强制两种索引执行四种排序方向，记录 `EXPLAIN` 的 Extra 与 `Handler_read%` 读取次数；按没有索引的 `score` 排序，在 32KB 与 64MB 两种 `sort_buffer_size` 下分别取前 10 行与最后 10 行，从优化器跟踪的 `filesort_summary` 与 `Sort_merge_passes` 读出是否用优先队列、溢出块数与合并趟数；按只有 50 个值的 `price` 用 `LIMIT offset, 10` 翻 10 页，统计不同 id 的个数，再加上 `id` 重复一次。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
