# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。场景名：`resync-backlog-1mb`、`resync-backlog-16kb`、`kill-primary`、`partition-async`、`partition-min-replicas`、`partition-wait`。

| 文件 | 内容 |
|---|---|
| `resync.tsv` | 每轮暂停前后 r1 的 `sync_full`、`sync_partial_ok` 与 backlog 状态 |
| `<场景>-writes.tsv` | `seq`、`status`（ack / unconfirmed / error / io_error / skipped）、`host`、`at_ms`、`latency_us`、`detail` |
| `<场景>-client.tsv` | 客户端事件：开始、连接、primary 变化、连接断开 |
| `<场景>-script.tsv` | 脚本事件：暂停、故障注入、新 primary、恢复 |
| `<场景>-sentinel.tsv` | s1 上 `PSUBSCRIBE *` 收到的事件（频道与内容） |
| `<场景>-replication-before.txt`、`-after.txt` | 三个节点 `INFO replication` 与同步计数的关键行 |
| `<场景>-old-primary.txt`、`<场景>-config.txt` | 分区恢复前旧 primary 的 DBSIZE 与角色；场景额外配置 |
| `<场景>-final-primary.txt`、`-final-seqs.txt` | 结束时的 primary 与其上全部 `f:*` 的序号 |
| `<场景>-server-events.log` | `docker compose logs --timestamps` 中与切换、同步相关的行 |
| `<场景>-timeline.tsv` | 汇总脚本生成：故障、最后确认、sdown、odown、switch-master、首次在新 primary 写入、核对结果 |
| `<场景>-client.log` | 客户端标准输出 |
| `assertions.txt` | 断言结果 |
| `container.txt`、`environment.txt` | 镜像、资源限制与运行环境 |

规范化：无。
