# 验证记录：Redis Cluster 分片与在线迁移

> 按 [验证标准](../../docs/verification-standard.md) 的十个部分记录。

## 一、待验证结论

1. 两个 key 在不同 slot 时，`MGET` 返回 `CROSSSLOT`（即使两个 slot 在同一节点）；JedisCluster 在客户端拒绝跨 slot 的 `MGET` 与 `EVAL`；hash tag 让它们落在同一个 slot。
2. 向不负责 slot 的节点请求，得到 `MOVED <slot> <地址>`。
3. 迁移 slot 途中：源节点对已搬走的 key 回复 `ASK`；目标节点对不带 `ASKING` 的请求回复 `MOVED`；带 `ASKING` 时返回值；迁移完成后旧节点回复 `MOVED`。
4. 手动迁移与 `--cluster reshard` 期间，JedisCluster 的读写零错误、零错误值；迁移前后 key 总数不变，slot 全覆盖。
5. 迁移一个大 key 时，`MIGRATE` 阻塞源节点上百毫秒。
6. slot 数量与 key 数、内存、访问量不对应：一个 hash tag 可以让某个节点的 key 数成倍于其他节点。
7. primary 被杀后，超过 node timeout 被判定失败，replica 提升；复制没有积压时已确认的写入不丢；客户端库的重试把故障表现为一次长时间的请求。

## 二、适用版本与环境

- Redis 8.10.1（`compose.yaml` 固定 digest），n1—n6 各 1 CPU、768 MB，`--cluster-enabled yes --cluster-node-timeout 5000 --save '' --appendonly no --latency-monitor-threshold 5`；`redis-cli --cluster create … --cluster-replicas 1` 以 n1—n3 为 primary。
- 客户端：JDK 21（固定 digest）运行 `src/ClusterClient.java`，Jedis 5.2.0、commons-pool2 2.12.0、json 20240303、gson 2.11.0、slf4j-api 1.7.36；种子节点 n1—n3，连接与读超时 1 秒，最多 10 次尝试、总计 20 秒。
- 运行环境见 `evidence/environment.txt`，镜像与资源见 `evidence/container.txt`。

## 三、场景与数据集

- 迁移与 reshard 的负载：每轮先 `SET` 再 `GET` 校验，一半请求落在迁移的 hash tag 下（1000 个 key），一半落在 `other:<n>`（1 万个 key）。
- 大 key：`{big}:hash`，50 万个字段，由 `redis-cli --pipe` 直接写入其所在节点。
- 倾斜：5 万个 `{hot}:<n>`，值 200 字节。
- 故障：每秒 200 次顺序写入 `fo:<序号>`，分散在各个 slot。

## 四、执行步骤

1. 组建集群，记录 `CLUSTER NODES`、`CLUSTER INFO`、`CLUSTER SHARDS`。
2. 记录 4 个 key 的 slot，用 `redis-cli -c` 执行跨 slot 的 `MGET`，用 JedisCluster 执行跨 slot 与同 slot 的 `MGET`、`EVAL`。
3. 向不负责 `user:1001:profile` 的节点直接 `GET`。
4. 启动负载 6 秒后，手动迁移 `{mig}` 所在的 slot：每批 100 个 key，第一批后分别向源、目标（带与不带 `ASKING`）读取同一个 key；完成后在所有节点 `SETSLOT … NODE`，负载再运行 5 秒。
5. 启动另一个负载 8 秒后，`--cluster reshard` 把 1000 个 slot 从 slot 0 的所有者迁到 slot 16383 的所有者，完成后运行 `--cluster check`。
6. 写入大 Hash，把它所在的 slot 迁到另一个 primary：源节点上同时运行 `redis-cli --latency-history`，记录 `MIGRATE` 前后时刻与 SLOWLOG。
7. 写入 5 万个 `{hot}` key，记录每个 primary 的 slot 数、DBSIZE、内存与 `SET` 调用次数。
8. 启动写入客户端 6 秒后杀掉 slot 8000 的所有者，每 0.5 秒记录一个存活节点看到的集群状态与被杀节点的标记，30 秒后停止客户端，汇总所有 primary 上的 `fo:*`。

## 五、原始证据索引

| 文件 | 内容 |
|---|---|
| `evidence/cluster-*.txt` | 组建输出与初始拓扑 |
| `evidence/slots.tsv`、`crossslot.tsv`、`moved.tsv` | slot 计算、跨 slot 结果、MOVED 回复 |
| `evidence/migrate.tsv`、`migrate-workload.tsv`、`migrate-errors.tsv` | 手动迁移的每一步与期间负载（每秒一行） |
| `evidence/reshard*.tsv`、`reshard-output.txt`、`reshard-check.txt` | reshard 前后 key 总数、原始输出、检查结果与期间负载 |
| `evidence/big-key-migrate.tsv` | 大 key 的大小、`MIGRATE` 前后时刻、源节点 PING 延迟、SLOWLOG |
| `evidence/skew.tsv` | 每个 primary 的 slot、key、内存与调用次数 |
| `evidence/failover*.tsv`、`failover-nodes-*.txt`、`failover-server-events.log`、`failover-final-seqs.txt` | 故障时刻、状态采样、每次写入、拓扑前后、节点日志、最终存在的序号 |
| `evidence/assertions.txt` | 断言结果 |

## 六、实际结果

| 结论 | 实际结果（`evidence/assertions.txt`） |
|---|---|
| 1 | slot 2549 与 4492（都在 n1）→ `CROSSSLOT`；hash tag 都在 5712；JedisCluster：`Keys must belong to same hashslot.` |
| 2 | `MOVED 2549 <n1 的地址>` |
| 3 | 迁移 slot 13513（n3 → n1）：`ASK`、`MOVED`、`ASKING` 后返回值、完成后 `MOVED` |
| 4 | 手动迁移 10 批、6.6 秒，259,888 次读写零错误；reshard 1000 个 slot 1.4 秒，237,870 次读写零错误；key 总数 12,004 → 12,004，16384 个 slot 全覆盖 |
| 5 | 24.5 MB、50 万字段：`MIGRATE` 212ms，源节点 PING 最长 206ms |
| 6 | n1 / n2 / n3：slot 4,463 / 5,461 / 6,460，key 3,727 / 54,328 / 3,947，内存 27.5 / 18.9 / 5.4 MB |
| 7 | 约 6.0s `fail?`，6.1s `FAIL` 与集群状态 fail，6.8s 授权 n6，6.9s 恢复 ok；已确认 9,577 条，丢失 0 条，写入错误 0 次，一次写入等待 7.8 秒 |

## 七、结果解释

- 跨 slot 的限制在命令层面检查 slot 而不是节点：迁移会让同一节点上的 slot 分开，所以协议只能按 slot 保证。
- `ASK` 只让下一条命令例外；目标节点在 `IMPORTING` 状态下只接受带 `ASKING` 的请求，否则仍把请求交还给当前的所有者。
- JedisCluster 收到 `MOVED` 会刷新映射，收到 `ASK` 会发 `ASKING` 重试，所以迁移对它透明；故障期间它在重试上限内反复刷新拓扑，直到新 primary 出现。
- `MIGRATE` 对单个 key 是同步的序列化、传输与反序列化，key 越大，源节点被占用越久。

## 八、误差、限制与不能推出的结论

- 所有节点在同一台机器上，网络与迁移速度远好于真实环境。
- 故障场景只覆盖进程被杀；网络分区下少数一侧 primary 继续接受写入的情况没有在 Cluster 中复现（Sentinel 实验中有同类场景）。
- 负载统计为每秒一行，p99 为每秒内的分位数。
- 只验证了 Jedis 5.2.0；其他客户端对 `ASK`、拓扑刷新与重试的实现不同。
- 大 key 的阻塞时间取决于 key 的类型、大小与机器性能。

## 九、复现与清理命令

```bash
make verify     # 约 4 分钟，输出到 build/run
make evidence   # 重新采集 evidence/
make clean      # 删除容器
```

## 十、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
