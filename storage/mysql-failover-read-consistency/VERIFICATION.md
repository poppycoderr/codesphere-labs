# 验证记录：故障切换与读一致性

> 按 [验证标准](../../docs/verification-standard.md) 的十个部分记录。

## 一、待验证结论

1. 异步复制：replica 与 source 分区后 source 崩溃，分区之后确认的写入在新 source 上缺失；按同一 `request_id` 重试不会产生重复。
2. 旧 source 重启后多出的事务数等于丢失的写入数；直接接回为 replica 时复制报错停止，两边数据已经分叉。
3. 半同步（等 1 个 ACK）且 replica1 暂停应用时，半同步一直有效；如果不先应用 relay log 就提升，会缺大量已确认写入；先应用完再提升，缺失为 0。
4. 两个 replica 都不可达时，半同步等待超时后退化为异步（`Rpl_semi_sync_source_status = OFF`），之后确认的写入在崩溃后缺失。
5. 客户端在半同步等待期间超时：无唯一键的表重试后同一请求出现 2 行；`request_id` 唯一的表重试命中已存在的行（`affected_rows = 0`），仍是 1 行。
6. 复制正常时写后立即读 replica 几乎全部读不到；读 source 或在 replica 上等待写入的 GTID 后全部读到；`SOURCE_DELAY = 1` 的 replica 上 GTID 等待超过 0.9 秒。

## 二、适用版本与环境

- MySQL 8.4.11（`compose.yaml` 固定 digest），1 个 source 与 2 个 replica，各限制 2 CPU、1.5 GB；`gtid_mode = ON`、`enforce_gtid_consistency = ON`、`replica_net_timeout = 15`，其余为默认值。
- 半同步：`semisync_source.so` 与 `semisync_replica.so`，等待点为默认的 `AFTER_SYNC`、等待 1 个 ACK；场景二超时 10 秒（默认值），场景三超时 2 秒。
- 客户端：JDK 21（固定 digest）与 Connector/J 8.0.27，连接参数 `trackSessionState=true&useAffectedRows=true`，写入的读超时 3 秒，重试歧义场景 1 秒。
- 故障注入：`docker kill -s KILL`（source）与 `docker network disconnect`（复制网络）。同一台机器上的 Docker。

## 三、场景与数据集

- `orders(id 自增, request_id 唯一, seq, created_at)`：客户端每 20ms 自动提交一笔，只有 `executeUpdate` 返回后才记为确认；写入失败时结果未知，换到新目标后用 `INSERT … ON DUPLICATE KEY UPDATE` 按同一 `request_id` 重试。
- `orders_no_key`：没有唯一键，用于重试歧义对照。
- 每个场景重建拓扑，客户端事件（UTC 与进程内单调时间）与编排脚本的时间线（UTC）分别记录。

## 四、执行步骤

1. 场景一（`async-partition`）：启动写入，断开两个 replica 的复制网络，3 秒后 SIGKILL source，恢复网络；每秒从 replica1 探测 source，连续 3 次失败判定故障；记录候选的已接收与已执行集合；在 replica1 上停止接收、应用完 relay log、`RESET REPLICA ALL`、关闭只读；replica2 改指向 replica1；切换客户端目标；等到第一笔新写入后停止客户端，导出新 source 上的序号。
2. 场景六（沿用场景一）：重启旧 source，比较它与新 source 的 GTID 集合，把它直接配置为新 source 的 replica，记录复制状态、错误与两边的行数和校验和。
3. 场景二（`semisync-applier-paused`）：开启半同步；replica2 停止接收，replica1 暂停 SQL 线程；4 秒后 SIGKILL source；提升前先导出 replica1 已执行的序号，再按场景一的流程提升。
4. 场景三（`semisync-timeout`）：半同步超时 2 秒；断开两个 replica 的复制网络，同时运行重试歧义客户端；之后 SIGKILL source 并切换。
5. 场景四：新拓扑，replica2 设置 `SOURCE_DELAY = 1`；每种策略写后立即读 300 次（replica2 为 30 次）。
6. `scripts/summarize.py` 对照确认序列与新 source 数据，计算缺失、重复与各阶段耗时并断言。

## 五、原始证据索引

| 文件 | 内容 |
|---|---|
| `evidence/<场景>-client.tsv` | 客户端每一笔写入的确认、失败与重试事件 |
| `evidence/<场景>-timeline.tsv` | 分区、故障、判定、提升、路由切换等编排事件 |
| `evidence/<场景>-candidates.txt` | 故障后每个 replica 的连接状态、已接收与已执行集合 |
| `evidence/<场景>-new-source-seqs.txt` | 新 source 上存在的订单序号 |
| `evidence/semisync-*-semisync-*.txt` | 半同步配置与故障前的状态变量 |
| `evidence/semisync-applier-paused-replica1-before-relay-apply-seqs.txt` | 提升前 replica1 已执行的序号 |
| `evidence/rejoin-old-source.tsv` | 旧 source 与新 source 的 GTID 集合、接回后的复制状态与数据差异 |
| `evidence/ambiguous.tsv` | 重试歧义的事件 |
| `evidence/read-after-write.tsv` | 四种读策略的读不到次数与耗时分位数 |
| `evidence/summary.md、assertions.txt` | 汇总与断言 |

`<场景>-client.tsv` 中 `client_ack`、`first_write_after_failover`、`retry_*` 行都是客户端已确认的写入；缺失 = 确认序号 − 新 source 上的序号。

## 六、实际结果

| 场景 | 已确认 | 缺失 | 重复 | 故障→判定 | 判定→提升完成 | 提升→路由 | 故障→第一笔新写入 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 异步分区 | 345 | 137 | 0 | 6.6s | 0.6s | 0.2s | 8.6s |
| 半同步，暂停应用 | 391 | 0（直接提升会缺 277） | 0 | 6.4s | 2.3s | 0.2s | 9.8s |
| 半同步超时 | 457 | 255 | 0 | 6.6s | 2.1s | 0.2s | 9.8s |

- 场景二故障前 `Rpl_semi_sync_source_yes_tx = 289`、`no_tx = 0`；场景三 `status = OFF`、`no_tx = 164`。
- 旧 source 多出 `…:125-261` 共 137 个事务；接回后 SQL 线程报 `Duplicate entry` 停止；`orders` 251 行对 208 行。
- 重试歧义：无唯一键 2 行；唯一键 `affected_rows = 0`，1 行。
- 写后读：replica1 直接读 300/300 读不到，GTID 等待中位 0.79ms；replica2 直接读 30/30 读不到，GTID 等待中位 2,010ms；读 source 全部读到。

## 七、结果解释

- 异步复制的丢失窗口是「source 已确认、replica 尚未收到」；分区让这个窗口持续 3 秒。
- 半同步的 ACK 在 replica 写入 relay log 后发出，与应用无关；relay log 中的事务在提升前应用即可找回。
- 超时退化后，source 不再等 ACK，保障与异步相同。
- 旧 source 多出的事务在新 source 的时间线上不存在；新 source 为新订单分配的自增主键与这些事务冲突，所以接回时报错。在没有冲突的数据上，分叉可能不会报错。
- 写后立即读发生在复制完成之前，与复制是否「正常」无关；`SOURCE_DELAY` 按秒级的原始提交时间计算，实际延迟接近 2 秒。

## 八、误差、限制与不能推出的结论

- 缺失笔数取决于写入速率与故障注入时机；只断言缺失 > 50、且缺失写入的确认时间在分区开始前 0.5 秒之后到崩溃之前。
- 故障判定用固定的 1 秒间隔、3 次失败，判定耗时是这个策略的结果，不代表任何工具的默认行为。
- 只验证经典异步与半同步复制；没有验证 InnoDB Cluster / Group Replication、MySQL Router、Orchestrator 等工具。
- 所有节点在同一台机器上，网络分区用 Docker 网络断开模拟。

## 九、复现与清理命令

```bash
make verify     # 约 8—10 分钟，输出到 build/run
make evidence   # 重新采集 evidence/
make clean      # 删除容器、网络与数据卷
```

## 十、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
