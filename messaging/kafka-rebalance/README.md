# Kafka 消费组重平衡

对应文章：[kafka-consumer-rebalance.md](https://github.com/poppycoderr/codesphere/blob/master/docs/messaging/kafka-consumer-rebalance.md)。Spring Kafka 下的默认值与迁移见 [spring/kafka-listener-group-protocol](../../spring/kafka-listener-group-protocol/)。

三节点 KRaft 集群（`compose.yaml`），客户端程序 `src/Rebalance.java` 在同一镜像的容器里运行。三种方式：`eager`（经典协议 + `RangeAssignor`，客户端默认）、`coop`（经典协议 + `CooperativeStickyAssignor`）、`consumer`（`group.protocol=consumer`）。

| 场景 | 内容 |
|---|---|
| `scale-healthy` | 6 个分区、每分区每秒约 10 条、每条处理 10ms；C1、C2 稳定后加入 C3，记录撤销、分配和每个分区的最长中断 |
| `scale-slow` | 同上，C2 在 C3 加入前 200ms 开始一次 8 秒的处理 |
| `dup` | 2 个分区、手动提交、`max.poll.interval.ms=5000`，C1 的第 3 批每条 80ms |
| `static` / `dynamic` | C1 关闭、3 秒后用同一个 `group.instance.id`（或新身份）重启，观察 C2 |
| `defaults` / `config` | 客户端默认值；新协议下设置 `session.timeout.ms` |

扩容场景每种方式运行两轮，`scripts/check.py` 汇总取值范围并断言相对关系。

## 快速运行

```bash
make verify     # 需要 Docker、JDK 21；约 12 分钟
make evidence
make clean      # 删除集群
```
