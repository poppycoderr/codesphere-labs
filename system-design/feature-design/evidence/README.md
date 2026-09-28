# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 同分排序、排行榜内存、GEO、红包模拟的结果（键、事实） |
| `benchmark.tsv` | `redis-benchmark` 的吞吐与 p50 |
| `slowlog.txt` | 取前 10 名、按名次深翻页、按分数偏移深翻页三条命令的 `SLOWLOG`（第三行是微秒） |
| `server.txt`、`environment.txt` | Redis 版本与运行环境 |
