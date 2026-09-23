# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

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
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容，GTID 中的 server_uuid 每次运行都会变化。
