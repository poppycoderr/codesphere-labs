# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `server.txt`、`lazyfs.txt` | Redis 版本；LazyFS 挂载信息与 commit |
| `crash-matrix.tsv` | 配置、故障类型、轮次、最后确认序号、最后确认时刻与 kill 时刻（毫秒）、重启到可服务的毫秒数、`存在的已确认序号数 最大序号 超出确认的序号数 DBSIZE` |
| `crash-*-config.txt` | 该次运行的 `save`、`appendonly`、`appendfsync` |
| `crash-*-writer.tsv`、`crash-*-writer.log` | 写入客户端的结果（最后确认序号、结束原因、延迟分位数）与标准输出 |
| `crash-*-redis.log` | 崩溃前与重启后的 Redis 日志 |
| `crash-*-power-*-files-at-crash.txt` | kill 之前 Redis 的 `aof_current_size`，以及此刻已 fsync 到底层目录的文件大小 |
| `write-latency.tsv`、`bench-*.txt`、`write-latency-delayed-fsync.txt` | 写延迟汇总、`redis-benchmark` 原始输出、`aof_delayed_fsync` |
| `multipart-aof.txt`、`multipart-aof-log.txt` | 重写前后的目录与 manifest、相关日志 |
| `fork-cow.txt`、`fork-cow-log.txt`、`fork-latency-history.txt`、`load-dataset.txt` | fork 耗时、写时复制、`LATENCY HISTORY fork`、数据集大小 |
| `load-times.tsv`、`load-*-files.txt` | 每种文件重启时的加载日志与文件列表 |
| `corrupt-setup.txt`、`corrupt-*.txt` | 损坏位置、启动结果、`redis-check-aof` 与 `--fix` 输出、修复后的 DBSIZE、被改写的值 |
| `backup.txt`、`backup-files.txt`、`backup-log.txt`、`backup-restored.tsv` | `BACKUP` 各步回复、备份文件与 manifest、日志、恢复后各类 key 的数量 |
| `assertions.txt` | 断言结果 |
| `container.txt`、`environment.txt` | 镜像、资源限制与运行环境 |

规范化：日志中的时间戳在 `multipart-aof-log.txt`、`fork-cow-log.txt`、`corrupt-*.txt`、`backup-log.txt` 中替换为 `<ts>`；其余为原始输出。
