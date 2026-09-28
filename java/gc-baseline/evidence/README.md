# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `flags-2g.txt`、`flags-4g.txt`、`flags-2g-ram70.txt` | 容器内的默认堆参数 |
| `gc-*-{1,2,3}.log`、`alloc-*.txt` | 每次运行的 GC 日志与分配量 |
| `summary.tsv` | GC 统计（3 次中位数）与 OOM 转储大小 |
| `heap-info.txt`、`jstat.txt` | 运行中进程的 `jcmd GC.heap_info` 与 `jstat -gcutil` 输出 |
| `oom-64k.log`、`oom-1024k.log` | OOM 与转储的输出 |
| `environment.txt` | 运行环境 |
