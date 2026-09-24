# 验证记录：Redis Sentinel 故障切换

> 按 [验证标准](../../docs/verification-standard.md) 的十个部分记录。

## 一、待验证结论

1. replica 断线期间的写入仍在 backlog 中时，重连后部分同步；超出 backlog 时全量同步。
2. primary 进程被 `SIGKILL`：约 `down-after-milliseconds` 后主观下线，几秒内完成切换，客户端恢复写入；复制没有积压时，已确认的写入不丢；旧 primary 重启后被改为 replica。
3. primary 被网络分区、客户端仍连着它：旧 primary 在切换完成前继续确认写入，分区恢复后它被改为新 primary 的 replica，这些写入全部丢失。
4. `min-replicas-to-write 1` + `min-replicas-max-lag 2`：旧 primary 在 max-lag 之后拒绝写入（`NOREPLICAS`），丢失减少但不为 0。
5. 每次写入后 `WAIT 1 100`：`WAIT` 确认过的写入不丢；分区期间的写入 `WAIT` 返回 0，客户端能识别它们为未确认。

## 二、适用版本与环境

- Redis 8.10.1（`compose.yaml` 固定 digest）：r1（初始 primary）、r2、r3，各 1 CPU、512 MB，关闭持久化；Sentinel s1—s3，各 0.5 CPU、128 MB，配置见 `config/sentinel.conf`（quorum 2，`down-after-milliseconds 3000`，`failover-timeout 10000`，`resolve-hostnames` 与 `announce-hostnames` 打开）。
- 网络：`data`（复制与 Sentinel 探测，节点别名 `rN-data`）与 `side`（客户端）。分区 = 把 r1 从 `data` 断开；恢复 = 以别名 `r1-data` 重新接入。
- 客户端：`src/Failover.java`（JDK 21，固定 digest），在 `side` 网络上；连接与命令超时 1 秒，每 200ms 依次询问 s1—s3 的 `SENTINEL get-master-addr-by-name`，另一线程订阅 s1 的全部事件。
- 运行环境见 `evidence/environment.txt`，镜像与资源见 `evidence/container.txt`。

## 三、场景与数据集

- 每个故障场景重建拓扑。客户端每秒写入 200 条 `f:<序号>`，值为序号；每条写入记录结果（`ack`、`unconfirmed`、`error`、`io_error`、`skipped`）、写入的节点、时刻与耗时。
- 部分 / 全量同步：同一个拓扑中先后把 r1 的 `repl-backlog-size` 设为 1 MB 与 16 KB，`repl-timeout 3`；`docker pause` r3，等 r1 断开 r3（`connected_slaves` 变为 1）后再写入一个 200 KB 的值，4 秒后恢复 r3。
- 杀进程：`docker kill r1`，切换完成后重新启动 r1。
- 分区：异步；`min-replicas-to-write 1` + `min-replicas-max-lag 2`（三个节点都设置）；每次写入后 `WAIT 1 100`。

## 四、执行步骤

1. 启动拓扑，等 Sentinel 发现 2 个 replica 与另外 2 个 Sentinel。
2. 启动客户端，确认已有写入成功后再等 3 秒。
3. 注入故障，记录时刻；等 Sentinel 返回新的 primary，再等客户端在新 primary 上写入成功。
4. 杀进程场景重启 r1；分区场景记录 r1 在恢复前的 DBSIZE 与复制状态，然后恢复网络。等 r1 变为 replica 且复制链路正常。
5. 记录三个节点的 `INFO replication` 与同步计数，停止客户端，导出最终 primary 上的全部序号与各容器日志中的相关行。
6. `scripts/summarize.py` 生成每个场景的时间线（`*-timeline.tsv`）并断言。

## 五、原始证据索引

| 文件 | 内容 |
|---|---|
| `evidence/resync.tsv`、`resync-backlog-*-script.tsv` | 同步计数前后值、backlog 大小与实际长度、暂停与写入时刻 |
| `evidence/<场景>-writes.tsv` | 每一次写入：序号、结果、节点、时刻、耗时、`WAIT` 返回值或错误 |
| `evidence/<场景>-client.tsv`、`<场景>-script.tsv` | 客户端事件（连接、切换）与脚本事件（故障注入、恢复） |
| `evidence/<场景>-sentinel.tsv` | s1 发布的全部 Sentinel 事件 |
| `evidence/<场景>-replication-before.txt`、`-after.txt`、`-old-primary.txt` | 三个节点的复制状态；分区恢复前旧 primary 的 DBSIZE 与角色 |
| `evidence/<场景>-final-primary.txt`、`-final-seqs.txt` | 最终 primary 与其上的全部序号 |
| `evidence/<场景>-server-events.log` | 各容器日志中与切换、同步相关的行 |
| `evidence/<场景>-timeline.tsv` | 由汇总脚本生成的 t0—t6 时间线 |
| `evidence/assertions.txt` | 断言结果 |

## 六、实际结果

| 结论 | 实际结果（`evidence/assertions.txt`） |
|---|---|
| 1 | backlog 1 MB：`sync_partial_ok` +1，`sync_full` +0；16 KB：`sync_full` +1 |
| 2 | +sdown 3.16s，+odown 3.23s，客户端 5.18s 恢复写入；已确认 4,627 条，丢失 0 条，失败 16 次；r1 重启后为 replica |
| 3 | +sdown 3.95s，+odown 4.02s；旧 primary 确认写入直到 5.23s，已确认的 1,028 条全部丢失；r1 恢复后为 replica |
| 4 | 旧 primary 3.0s 起返回 `NOREPLICAS`（368 次），丢失 584 条 |
| 5 | `WAIT` 确认的写入丢失 0 条；32 条返回未确认，最终都不存在 |

## 七、结果解释

- 分区时 r1 与客户端之间的连接正常，r1 并不知道自己已经被取代，只要客户端还在用它，就继续确认写入；Sentinel 把 r1 改为新 primary 的 replica 后，r1 的复制历史与新 primary 分叉，只能全量同步，独有的写入被覆盖。
- `min-replicas-max-lag` 看的是 replica 最近一次 ACK 的时间，replica 每秒 ACK 一次，所以拒绝写入的时刻约为 max-lag 加上不超过 1 秒。
- `WAIT` 在超时后返回已确认的副本数，不撤销写入；分区期间 r1 上的这些写入虽被执行，最终仍随 r1 的全量同步消失，所以「未确认」的写入全部不存在，但这不是一般规律：切换前已经复制出去的未确认写入可能保留下来。

## 八、误差、限制与不能推出的结论

- 所有节点在同一台机器上，网络延迟远低于真实环境；分区用 `docker network disconnect` 模拟，是干净的断开，没有丢包、半开连接等情况。
- 时间点取决于 Sentinel 的探测周期、选举的随机延迟与客户端的轮询间隔，每次运行相差 1 秒左右；丢失条数随之变化，只说明量级与相对关系。
- `+switch-master` 取自 s1 订阅到的事件，客户端可能先从其他 Sentinel 得到新地址。
- 分区前的写入延迟受 JVM 预热影响，两次运行中 `SET` 与 `SET + WAIT 1` 的对比不稳定，没有作为结论。
- 没有覆盖 Sentinel 自身被分区、多个 primary 同时存在更久、客户端库的具体实现（Jedis、Lettuce）与 `WAITAOF`。

## 九、复现与清理命令

```bash
make verify     # 约 6 分钟，输出到 build/run
make evidence   # 重新采集 evidence/
make clean      # 删除容器与网络
```

## 十、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
