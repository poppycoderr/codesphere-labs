# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `run-*.txt`、`gc-*.log` | 每次负载运行的程序输出（吞吐、CPU、探针分位数）与 GC 日志 |
| `explicit-*.txt`、`gc-explicit-*.log` | `System.gc()` 的调用耗时与 GC 日志 |
| `summary.tsv` | 汇总：每次运行的停顿次数、合计、最长、Full GC、分配停顿，以及各类停顿的次数 |
| `environment.txt` | 运行环境 |
