# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。节点地址是 Docker 网络内的私有 IP，`cluster-nodes-*.txt` 与 `failover-nodes-*.txt` 已把 IP 换成服务名（n1—n6）。

| 文件 | 内容 |
|---|---|
| `cluster-create.txt`、`cluster-nodes-initial.txt`、`cluster-info-initial.txt`、`cluster-shards-initial.txt` | 组建输出与初始拓扑 |
| `slots.tsv`、`crossslot.tsv`、`crossslot.log`、`moved.tsv` | slot 计算、`redis-cli -c` 与 JedisCluster 的跨 slot 结果、MOVED 回复 |
| `migrate.tsv` | 手动迁移：slot、源、目标、每一步的回复、迁移中的拓扑、批次数、迁移前后 key 数 |
| `migrate-workload.tsv`、`reshard-workload.tsv` | 每秒一行：`ops`、`errors`、`wrong_values`、`p50_us`、`p99_us`、`max_us` |
| `migrate-errors.tsv`、`reshard-errors.tsv` | 负载的错误明细（为空表示没有错误） |
| `reshard.tsv`、`reshard-output.txt`、`reshard-check.txt` | reshard 前后 key 总数与起止时刻、`--cluster reshard` 与 `--cluster check` 原始输出 |
| `big-key-migrate.tsv` | 大 Hash 的字段数与内存、源节点 `--latency-history`（最小、最大、平均毫秒与样本数）、SLOWLOG |
| `skew.tsv` | 每个 primary 的 slot 数、DBSIZE、`used_memory`、`SET` 调用次数；热点 slot 与所有者 |
| `failover.tsv`、`failover-state.tsv`、`failover-writes.tsv` | 被杀节点与时刻、每 0.5 秒的集群状态与标记、每次写入的结果与耗时 |
| `failover-nodes-before.txt`、`failover-nodes-after.txt`、`failover-server-events.log`、`failover-final-seqs.txt` | 故障前后拓扑、节点日志中的相关行、最终存在的序号 |
| `*-client.log` | 客户端标准输出 |
| `assertions.txt` | 断言结果 |
| `container.txt`、`environment.txt` | 镜像、资源限制与运行环境 |

规范化：拓扑文件中的 IP 替换为服务名，节点 ID 截取前 8 位；其余为原始输出。
