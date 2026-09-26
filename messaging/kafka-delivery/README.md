# Kafka 投递语义的边界

对应文章：[kafka-delivery-semantics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/messaging/kafka-delivery-semantics.md)。

1 个独立 controller + 3 个 broker 的 KRaft 集群（`compose.yaml`），客户端程序 `src/Delivery.java` 在同一镜像的容器里运行。controller 单独部署，是为了停掉两个 broker 之后控制面仍然可用、ISR 能够收缩。

| 场景 | 内容 |
|---|---|
| `tx` | 读—处理—写：第一轮写出 10 条后中止事务，第二轮重新处理并提交 |
| `fence` | 同一个 `transactional.id` 启动新实例后，旧实例继续发送或直接提交 |
| `autocommit` | 自动提交（100ms）+ 单线程异步处理（每条 50ms），2 秒后退出 |
| `isr-*` | 3 副本 topic，停掉两个 follower 后 `acks=all` 与 `acks=1` 的结果；`min.insync.replicas` 为 2 与默认 1 对照 |

消费方按事件 ID 去重的验证见 [ddd/context-integration-outbox](../../ddd/context-integration-outbox/)。

## 快速运行

```bash
make verify     # 需要 Docker、JDK 21；约 2 分钟
make evidence
make clean      # 删除集群
```
