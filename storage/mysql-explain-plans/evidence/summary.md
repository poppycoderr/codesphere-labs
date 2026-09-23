<!-- 由 scripts/summarize.py 生成；EXPLAIN 各列来自 explain.tsv，耗时为 EXPLAIN ANALYZE 根节点结束时间（多次采样取中位数） -->

| 查询 | type | key | key_len | rows（估算） | filtered | Extra | Handler 读取 | ICP 检查 | 中位数 ms | 采样 |
|---|---|---|---|---:|---:|---|---:|---:|---:|---:|
| `a01-ref-customer` | ref | idx_customer_created | 4 | 20 | 100.00 | NULL | 21 | 0 | 0.0462 | 1 |
| `a02-all-created` | ALL | NULL | NULL | 99841 | 33.33 | Using where | 100,003 | 0 | 19.4 | 1 |
| `a03-range-two-columns` | range | idx_customer_created | 9 | 8 | 100.00 | Using index condition | 9 | 9 | 0.034 | 1 |
| `a04-covering` | range | idx_customer_created | 9 | 8 | 100.00 | Using where; Using index | 9 | 0 | 0.0136 | 1 |
| `a05-sort-amount` | ref | idx_customer_created | 4 | 20 | 100.00 | Using filesort | 21 | 0 | 0.0621 | 1 |
| `a06-sort-created` | ref | idx_customer_created | 4 | 20 | 100.00 | Backward index scan | 10 | 0 | 0.048 | 1 |
| `a07-phone-string` | ref | idx_phone | 82 | 1 | 100.00 | NULL | 2 | 0 | 0.0135 | 1 |
| `a08-phone-number` | ALL | NULL | NULL | 99841 | 10.00 | Using where | 100,003 | 0 | 19.8 | 1 |
| `a09-top-paid` | index | idx_customer_created | 9 | 99841 | 10.00 | Using where; Using temporary; Using filesort | 100,002 | 0 | 60.4 | 7 |
| `a10-date-function` | ALL | NULL | NULL | 99841 | 100.00 | Using where | 100,003 | 0 | 19.3 | 1 |
| `a11-date-range` | ALL | NULL | NULL | 99841 | 11.11 | Using where | 100,003 | 0 | 19.5 | 1 |
| `a12-column-arithmetic` | ALL | NULL | NULL | 99841 | 100.00 | Using where | 100,003 | 0 | 18.8 | 1 |
| `a13-like-suffix` | ALL | NULL | NULL | 99841 | 11.11 | Using where | 100,003 | 0 | 25.0 | 1 |
| `a14-like-suffix-cover` | index | idx_phone | 82 | 99841 | 11.11 | Using where; Using index | 100,002 | 0 | 19.0 | 1 |
| `a15-like-prefix` | range | idx_phone | 82 | 10 | 100.00 | Using index condition | 11 | 11 | 0.0298 | 1 |
| `a16-or-both-indexed` | index_merge | idx_customer_created,idx_phone | 4,82 | 21 | 100.00 | Using sort_union(idx_customer_created,idx_phone); Using where | 63 | 0 | 0.0618 | 1 |
| `a17-or-one-unindexed` | ALL | NULL | NULL | 99841 | 10.02 | Using where | 100,003 | 0 | 20.9 | 1 |
| `a18-not-equal` | ALL | NULL | NULL | 99841 | 50.82 | Using where | 100,003 | 0 | 19.6 | 1 |
| `b01-top-paid-covering` | ref | idx_status_customer | 66 | 47252 | 100.00 | Using index; Using temporary; Using filesort | 25,001 | 0 | 5.23 | 7 |
| `c01-date-range-indexed` | range | idx_created | 5 | 415 | 100.00 | Using index condition | 416 | 416 | 0.391 | 1 |
| `c02-date-function` | ALL | NULL | NULL | 99861 | 100.00 | Using where | 100,003 | 0 | 19.1 | 1 |
| `d01-status-index` | ref | idx_status | 66 | 33280 | 100.00 | NULL | 25,001 | 0 | 15.1 | 7 |
| `d02-status-table-scan` | ALL | NULL | NULL | 99841 | 33.33 | Using where | 100,003 | 0 | 19.8 | 7 |
| `e01-equality-first` | range | idx_status_created | 71 | 16788 | 100.00 | Using index condition | 9,223 | 9,223 | 7.08 | 7 |
| `e02-range-first` | range | idx_created_status | 71 | 1 | 10.00 | Using index condition | 9,223 | 36,899 | 8.97 | 7 |
