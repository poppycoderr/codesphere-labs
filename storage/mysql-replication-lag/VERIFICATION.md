# 验证记录：复制延迟：一笔事务怎样抵达副本

> 按 [验证标准](../../docs/verification-standard.md) 的十个部分记录。

## 一、待验证结论

1. 稳态下（source 每 50ms 一条心跳）两个 replica 的业务序号差不超过几条。
2. source 上一个更新 100 万行的大事务提交后，replica 才开始应用它；应用期间后续心跳已被接收（`RECEIVED_TRANSACTION_SET` 包含它们）但全部排队未应用，IO 与 SQL 线程始终正常。
3. 并行回放：积压 2 万个更新互不相关行的事务，4 个 worker 的追平时间明显短于 1 个，事务均匀分配；全部更新同一行时，4 个 worker 与 1 个耗时相当，事务几乎全部落在一个 worker 上。
4. replica 上同时运行全表查询时，同样积压的追平时间显著变长。
5. 把 replica 从复制网络断开后，在 `replica_net_timeout` 到期之前，IO 状态仍为 `ON`、`Seconds_Behind_Source` 仍为 0，业务序号持续落后；到期后才变为 `CONNECTING` 与 `NULL`；网络恢复后重新连接并追平。
6. 实验结束时三个节点的 GTID 集合与三张业务表的 `CHECKSUM TABLE` 完全相同。

## 二、适用版本与环境

- MySQL 8.4.11（`compose.yaml` 固定 digest），1 个 source 与 2 个 replica，各限制 2 CPU、1.5 GB；配置见 `config/common.cnf`：`gtid_mode = ON`、`enforce_gtid_consistency = ON`、`replica_net_timeout = 15`，其余为默认值（`binlog_format = ROW`、`sync_binlog = 1`、`replica_parallel_workers = 4`、`replica_preserve_commit_order = ON`）。
- 复制：`SOURCE_AUTO_POSITION = 1`、`SOURCE_SSL = 1`、`SOURCE_CONNECT_RETRY = 5`；replica 在建立复制前执行 `RESET BINARY LOGS AND GTIDS`，之后 `super_read_only = ON`。
- 客户端：JDK 21（固定 digest）与 Connector/J 8.0.27，运行在独立的客户端网络上。
- 同一台机器上的 Docker，网络延迟远小于真实环境。拓扑与参数的实际值见 `evidence/topology.txt`。

## 三、场景与数据集

- `heartbeat(seq, written_at)`：source 上每 50ms 自动提交一条，序号单调递增；客户端在 `executeUpdate` 返回后才推进「已确认序号」。
- `accounts`：1 万行，用于并行回放对照（独立行与同一行）。
- `big`：100 万行，用于大事务。
- 采样：每 250ms 读取每个 replica 的 `MAX(seq)`、`SHOW REPLICA STATUS` 的 `Seconds_Behind_Source` 与 SQL 线程状态、`replication_connection_status.SERVICE_STATE`、`GTID_SUBTRACT(RECEIVED_TRANSACTION_SET, gtid_executed)` 的事务数、worker 正在应用的事务距原始提交的毫秒数。

## 四、执行步骤

1. 启动三个节点并配置复制，建表造数，等待两个 replica 追平。
2. 稳态基线：写心跳并采样 12 秒。
3. 大事务：2 秒后在 source 上执行 `UPDATE big SET v = v + 1`，持续采样直到两个 replica 连续 2 秒没有差距。
4. 并行回放：停止 replica1 的 SQL 线程并设置 worker 数，source 上 16 个客户端提交 20,000 个单行 `UPDATE`，清空事务统计后启动 SQL 线程，用 `WAIT_FOR_EXECUTED_GTID_SET` 计时，再从 `events_transactions_summary_by_thread_by_event_name` 读取每个 worker 执行的事务数；独立行与同一行各测 1 个与 4 个 worker。
5. replica 查询负载：4 个 worker、独立行，追平期间 replica1 上两个会话循环执行全表聚合。
6. 链路静默中断：心跳写入与采样运行 60 秒，第 8 秒把 replica2 从 `repl` 网络断开，30 秒后重新接入。
7. 等待追平，记录三个节点的 GTID 集合与 `CHECKSUM TABLE`。

## 五、原始证据索引

| 文件 | 内容 |
|---|---|
| `evidence/topology.txt` | 三个节点的 server_id、server_uuid、GTID 与复制参数，replica1 的复制状态 |
| `evidence/baseline-*.tsv` | 稳态的客户端事件与采样 |
| `evidence/big-transaction-*.tsv` | 大事务的客户端事件与采样 |
| `evidence/parallel.tsv、busy-replica.tsv` | 并行回放与查询负载的追平耗时和 worker 分配 |
| `evidence/stall-*.tsv、stall-replica2-status-after.txt` | 链路静默中断的采样、网络事件与恢复后的复制状态 |
| `evidence/final-checksums.tsv` | 三个节点最终的 GTID 集合与表校验和 |
| `evidence/assertions.txt` | 断言结果 |
| `evidence/container.txt` | 镜像与资源限制 |

`*-samples.tsv` 每行一条采样，字段见表头；`*-events.tsv` 记录客户端事件，UTC 时间与进程内单调时间（毫秒）并列。

## 六、实际结果

| 结论 | 实际结果（`evidence/assertions.txt`） |
|---|---|
| 稳态 | 两个 replica 的业务序号差最大 1 条 |
| 大事务 | source 上执行 2,287ms；提交后 replica1 最多落后 70 条心跳，其中 71 个事务已收到未应用；正在应用的事务距 source 提交最长 3,608ms；期间 `Seconds_Behind_Source` 取值 0—4 |
| 并行回放 | 独立行 1 个 worker 11.9s、4 个 worker 6.2s（分配 5,084 / 5,043 / 4,972 / 4,905）；同一行 10.7s 与 10.9s（分配 19,998 / 4 / 1 / 1） |
| 查询负载 | 追平 6.4s → 16.7s |
| 静默中断 | 断开于 18:11:58，IO 状态 `ON`、SBS 为 0 的采样持续到 18:12:13，业务最多落后 281 条心跳；之后为 `CONNECTING` 与 `NULL`；18:12:28 恢复网络，约 6 秒后重连并追平 |
| 最终一致 | 三个节点的 GTID 集合与三张表的校验和相同 |

## 七、结果解释

- 大事务在 source 提交之前不会出现在 binlog 中，replica 只能在提交之后开始应用；`replica_preserve_commit_order = ON` 让后续小事务必须排在它后面提交，所以「已接收未应用」持续增长。
- source 按 writeset 生成依赖：更新不同行的事务之间没有依赖，可以分给不同 worker；更新同一行的事务前后依赖，只能串行。
- 链路静默中断时，replica 的 TCP 连接没有收到任何错误，只能等 `replica_net_timeout` 内收不到数据才判定断线；在此之前 IO 线程的状态与 SBS 都不会变化。

## 八、误差、限制与不能推出的结论

- 所有节点在同一台机器上，网络延迟与带宽远好于真实环境；耗时只说明相对关系。
- 并行回放的收益取决于 CPU 数、存储与事务形态，2 CPU 的容器上 4 个 worker 约缩短一半，不能外推到其他规模。
- 采样间隔 250ms，心跳间隔 50ms，落后条数有 ±5 条的量化误差。
- 没有验证 Group Replication、多源复制与跨地域网络。
- `SOURCE_CONNECT_RETRY` 与 `replica_net_timeout` 都改小了；默认值（60 秒）下发现与重连都会更慢。

## 九、复现与清理命令

```bash
make verify     # 约 4 分钟，输出到 build/run
make evidence   # 重新采集 evidence/
make clean      # 删除三个容器、两个网络与数据卷
```

## 十、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
