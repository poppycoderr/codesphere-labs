# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

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
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容，GTID 中的 server_uuid 每次运行都会变化。
