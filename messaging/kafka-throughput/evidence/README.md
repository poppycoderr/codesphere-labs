# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `summary.tsv` | 五组实验的汇总（批处理取 3 轮中位数） |
| `perf.tsv` | `kafka-producer-perf-test` 的原始输出（含中间报告行）与生产者指标 |
| `partitions.tsv` | 分区并行与热点 key 的结果 |
| `container.txt` | 镜像与资源限制 |
| `environment.txt` | 运行环境 |
