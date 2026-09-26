# Spring Kafka 的重平衡协议

对应文章：[kafka-consumer-rebalance.md](https://github.com/poppycoderr/codesphere/blob/master/docs/messaging/kafka-consumer-rebalance.md)。原生客户端的对比实验见 [messaging/kafka-rebalance](../../messaging/kafka-rebalance/)。

一个最普通的 `@KafkaListener`，Kafka 由 Testcontainers 启动（`apache/kafka:4.3.1`，固定 digest）：

| 测试 | 配置 |
|---|---|
| `ListenerDefaultsTest` | 默认 |
| `ListenerNewProtocolTest` | `spring.kafka.consumer.properties[group.protocol]=consumer` |
| `ListenerNewProtocolConflictTest` | 新协议 + `session.timeout.ms=30000` |

协议与分配器从 Kafka 客户端启动时打印的 `ConsumerConfig values` 中读取。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven 与 Docker；约 1 分钟
make evidence
make clean
```
