<!-- 由 scripts/summarize.py 生成；7 次采样，MySQL 为会话内 NOW(6) 差值，ClickHouse 为 query_log.query_duration_ms -->

| 查询 | MySQL 中位 ms（最小—最大） | ClickHouse 中位 ms（最小—最大） | ClickHouse 读取行数 | 读取字节 | 结果一致 |
|---|---:|---:|---:|---:|---|
| `q1-status-aggregate` | 1645.44（1635.44—1661.90） | 44（41—45） | 5,000,000 | 110.0MB | 是 |
| `q2-monthly-range` | 1004.87（995.31—1010.52） | 19（18—20） | 1,794,048 | 21.5MB | 是 |
| `q3-point-lookup` | 0.33（0.29—0.35） | 7（7—8） | 1,097,728 | 5.5MB | 是 |
