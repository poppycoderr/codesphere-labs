<!-- 由 scripts/summarize.py 生成；耗时为 EXPLAIN ANALYZE 根节点 actual time 的结束值 -->

| 分组 | 查询 | 使用的索引 | Handler 读取次数 | 中位数 ms | 最小 ms | 最大 ms | 缓冲池页请求 | 物理读 | 返回行数 | 结果 SHA-1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| after | `01-force-index` | idx_state_event_deleted | 24,543 | 27.5 | 27.2 | 27.8 | 98,232 | 0 | 100 | `018db53b361c` |
| after | `02-prefer-ordering-index-off` | idx_state_event_deleted | 24,543 | 27.5 | 26.9 | 27.7 | 98,261 | 0 | 100 | `018db53b361c` |
| after | `03-order-by-expression` | idx_state_event_deleted | 24,543 | 27.7 | 27.2 | 28.0 | 98,232 | 0 | 100 | `018db53b361c` |
| after | `04-union-all` | idx_state_event_deleted | 401 | 0.448 | 0.426 | 0.452 | 1,226 | 0 | 100 | `018db53b361c` |
| before | `00-original` | PRIMARY | 2,850,622 | 569.0 | 564.0 | 585.0 | 49,171 | 0 | 100 | `018db53b361c` |
| control | `10-control-processed-at-head` | PRIMARY | 205 | 0.077 | 0.075 | 0.087 | 52 | 0 | 100 | `fbfe7e9e4897` |
| control | `11-control-single-value` | idx_state_event_deleted | 100 | 0.16 | 0.156 | 0.17 | 615 | 0 | 100 | `df3273dce9ff` |
