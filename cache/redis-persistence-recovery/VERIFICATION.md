# 验证记录：Redis 持久化与恢复

> 按 [验证标准](../../docs/verification-standard.md) 的十个部分记录。

## 一、待验证结论

1. 客户端收到 `OK` 时命令已经 `write()` 进 page cache：进程崩溃（`SIGKILL`）时，AOF `no`、`everysec`、`always` 都不丢已确认的写入。
2. 断电（丢弃未 fsync 的数据）时，`everysec` 丢失最后一次 fsync 之后的写入，约 1 秒以内，恢复出的是连续前缀；`always` 不丢。
3. 默认 save 规则下，6 秒的写入不会触发 RDB 快照，无论哪种故障都全部丢失；无持久化同样全部丢失。
4. `always` 显著降低单连接吞吐、提高延迟；并发连接越多，fsync 被合并，差距越小。
5. multi-part AOF：base（RDB 格式）+ incr + manifest；`BGREWRITEAOF` 期间主进程写新的 incr，完成后替换 manifest 并删除旧文件，数据完整。
6. `BGSAVE` 的写时复制量取决于快照期间修改的内存，空闲时很小，持续随机覆盖写时明显增大。
7. 同样 100 万个 key，RDB 与带 RDB 前导的 AOF 加载快、文件小；纯命令 AOF 与只有 incr 的 AOF 更大更慢。
8. AOF 尾部截断：默认配置截掉半条命令后启动；`aof-load-truncated no` 拒绝启动；命令头损坏拒绝启动，`redis-check-aof --fix` 截断损坏点之后的全部内容；值内部的损坏不会被发现。
9. `BACKUP START / SEAL` 生成的备份用 `preload-file` 恢复，得到 `SEAL` 那一刻的数据；误执行 `FLUSHALL` 后重启原实例，AOF 重放出空库。

## 二、适用版本与环境

- Redis 8.10.1（`docker/Dockerfile` 以固定 digest 的官方镜像为基础）与 LazyFS（固定 commit `fa7d32e`，见 `evidence/lazyfs.txt`），单容器 2 CPU、3 GB。LazyFS 以单线程模式运行，缓存 512 MB，关闭淘汰。
- 进程崩溃在容器可写层（overlay）上进行，断电在 LazyFS 挂载点上进行；写延迟、fork、加载、损坏与备份场景在容器可写层上进行。
- 写入客户端：`src/Writer.java`（JDK 21，固定 digest），单连接顺序写入，每秒 1000 条，收到 `OK` 才推进「最后确认序号」。
- 运行环境见 `evidence/environment.txt`，镜像与资源见 `evidence/container.txt`。

## 三、场景与数据集

- 崩溃矩阵：key 为 `w:<序号>`，值为序号本身；每个场景从空目录开始。
- 写延迟：`redis-benchmark -t set -n 40000 -r 100000 -d 64`，1 个与 32 个连接。
- multi-part AOF：20 万个 64 字节值的 key，重写期间再写 5 万个。
- fork 与加载：100 万个 100 字节值的 key；忙碌场景用 4 个客户端 `-P 16` 随机覆盖写。
- 损坏：1 万个 32 字节值的 key，只有 incr 文件（关闭自动重写）。
- 备份：a、b、c 三类各 1000 个 key。
- 所有数据由 `scripts/gen.py` 按序号生成，没有随机数。

## 四、执行步骤

1. 构建镜像并启动容器，确认 LazyFS 挂载。
2. 崩溃矩阵：启动 Redis → 启动写入客户端 → 6 秒后 `pkill -9 redis-server` → 断电场景向 LazyFS 发送 `lazyfs::clear-cache`，并等到缓存视图与底层目录的文件大小一致 → 用相同配置重启 → 用 Lua 统计 1..最后确认序号中存在的个数、最大序号与之后多出的序号。进程崩溃 5 种配置各 1 次；断电无持久化、RDB 各 1 次，`everysec` 5 次，`always` 3 次。
3. 写延迟：四种配置各启动一个新实例，分别用 1 个与 32 个连接压测。
4. multi-part AOF：记录重写前后的目录、manifest 与日志。
5. fork：空闲时与持续写入时各 `BGSAVE` 一次，记录 `latest_fork_usec`、`rdb_last_cow_size` 与 `LATENCY HISTORY fork`；加载：同一份数据依次保存为只有 incr 的 AOF、纯命令 AOF、RDB 前导 AOF、RDB，每次干净关闭后重启，读取日志中的加载耗时。
6. 损坏：复制出四份 AOF，分别截断尾部 5 字节（两份，一份用 `aof-load-truncated no` 启动）、在 `c:5000` 的命令头与值内部写入 `GARBAGE`，记录启动结果、`redis-check-aof` 与 `--fix` 的输出、修复后的 DBSIZE 与被改写的值。
7. 备份：写 a → `BACKUP START` → 写 b → `BACKUP SEAL` → 写 c → `FLUSHALL`；复制备份目录，原实例重启，新实例用 `preload-file` 加载备份。

## 五、原始证据索引

| 文件 | 内容 |
|---|---|
| `evidence/crash-matrix.tsv` | 每次崩溃的最后确认序号、确认与 kill 时刻、重启耗时、存在的序号统计 |
| `evidence/crash-<配置>-<故障>-<轮次>-*` | 配置、客户端结果与日志、Redis 日志；断电场景另有崩溃时刻的 `aof_current_size` 与底层文件大小 |
| `evidence/write-latency.tsv`、`bench-*.txt`、`write-latency-delayed-fsync.txt` | 写延迟汇总与 `redis-benchmark` 原始输出 |
| `evidence/multipart-aof.txt`、`multipart-aof-log.txt` | 重写前后的目录、manifest 与日志 |
| `evidence/fork-cow.txt`、`fork-cow-log.txt`、`fork-latency-history.txt`、`load-dataset.txt` | fork 与写时复制 |
| `evidence/load-times.tsv`、`load-*-files.txt` | 四种文件的加载日志与文件列表 |
| `evidence/corrupt-*.txt` | 损坏的构造、启动结果、`redis-check-aof` 输出与修复结果 |
| `evidence/backup*.txt`、`backup-restored.tsv` | `BACKUP` 各步回复、备份文件与 manifest、恢复后的 key 数 |
| `evidence/assertions.txt` | 断言结果 |

## 六、实际结果

| 结论 | 实际结果（`evidence/assertions.txt`） |
|---|---|
| 1 | 进程崩溃：`no` 5,746、`everysec` 5,753、`always` 5,744 条全部恢复 |
| 2 | 断电：`everysec` 5 次丢 737、582、906、906、910 条，均为连续前缀；`always` 3 次丢 0 |
| 3 | 无持久化与默认 RDB，两种故障都只剩 0 条 |
| 4 | 单连接：`everysec` 20,801 次/秒、p50 0.047ms，`always` 3,584 次/秒、p50 0.271ms；32 个连接时 `always` 是 `everysec` 的 27% |
| 5 | 重写后 manifest 为 `2.base.rdb` + `2.incr.aof`，DBSIZE 250,000 |
| 6 | 写时复制：空闲 1.0 MB，持续写入 63.0 MB；fork 1.7ms / 2.1ms |
| 7 | RDB 30.8 MB 0.55s；RDB 前导 AOF 30.8 MB 0.60s；纯命令 AOF 137.8 MB 0.76s；只有 incr 137.8 MB 0.76s |
| 8 | 截断：DBSIZE 9,999；严格模式拒绝启动；命令头损坏拒绝启动，`--fix` 后剩 5,000；值内部损坏：DBSIZE 10,000，`c:5000` 的值以 `GARBAGE` 开头 |
| 9 | 恢复出 a 1,000、b 1,000、c 0；原实例重启后 DBSIZE 0 |

## 七、结果解释

- Redis 在回复客户端之前把 `aof_buf` 写入文件（进入 page cache），`everysec` 由后台线程每秒 fsync。进程被杀时 page cache 由内核保留，之后照常写回；断电时未 fsync 的部分消失，所以丢的正好是最后一次 fsync 之后的一段连续写入。
- `always` 在回复前 fsync，同一轮事件循环内的多个客户端共享一次 fsync，所以并发越高，每次写入分摊的 fsync 成本越低。
- 写时复制只复制被修改的内存页；随机覆盖写会触及大量页，于是复制量明显增加。
- `redis-check-aof --fix` 找到第一个无法解析的位置后截断文件，损坏之后的所有命令都被丢弃。AOF 是协议文本，没有逐条校验，只要损坏后仍符合协议就会被加载。

## 八、误差、限制与不能推出的结论

- 断电用 LazyFS 模拟：它丢弃的是自己缓存中未 fsync 的数据，不模拟磁盘写缓存、文件系统日志与硬件行为。`appendfsync no` 在 LazyFS 中永远不会回写，与 Linux 约 30 秒的回写不同，因此没有做断电实验。
- 实验中发现：写入进行时经过 FUSE 读取挂载目录（`ls`）会让这个版本的 LazyFS 把后面的页写到文件开头。因此崩溃前只读取底层目录与 Redis 的 `INFO`，崩溃矩阵断言恢复出的必须是连续前缀，任何此类错误都会让断言失败。
- `everysec` 丢失的量取决于 fsync 与故障时刻的相位，5 次采样给出的是这次运行的分布，不是上界。
- 写延迟、fork 与加载耗时来自 Docker 虚拟磁盘上的小数据集，不能外推到其他存储与数据规模；fork 耗时随页表增大而增加。
- 没有覆盖 `appendfsync always` 下的磁盘满、AOF 写入失败（`aof_last_write_status:err`）与副本上的持久化。

## 九、复现与清理命令

```bash
make verify     # 约 8 分钟，输出到 build/run
make evidence   # 重新采集 evidence/
make clean      # 删除容器（保留构建的镜像；删除镜像：docker image rm csl-redis-lazyfs:8.10.1）
```

## 十、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
