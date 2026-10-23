# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `flags-2g.txt`、`flags-4g.txt`、`flags-2g-ram70.txt` | 容器内的默认堆参数 |
| `gc-jdk{21,25}-*-{1,2,3}.log`、`alloc-*.txt` | 两个 JDK 上每次运行的 GC 日志与分配量 |
| `summary.tsv` | GC 统计（3 次中位数，Full GC 列出每次的次数）与 OOM 转储大小 |
| `heap-info.txt`、`jstat.txt` | 运行中进程的 `jcmd GC.heap_info` 与 `jstat -gcutil` 输出 |
| `oom-64k.log`、`oom-1024k.log` | OOM 与转储的输出 |
| `jdk-versions.txt` | 两个镜像的 `java -version` |
| `environment.txt` | 运行环境 |
