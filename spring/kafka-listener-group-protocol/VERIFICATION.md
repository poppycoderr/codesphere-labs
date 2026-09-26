# 验证记录：Spring Kafka 的重平衡协议

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **默认值**：不做任何配置时，`@KafkaListener` 的消费者使用 `group.protocol = classic`，`partition.assignment.strategy` 为 `[RangeAssignor, CooperativeStickyAssignor]`，扩容时按 eager 方式撤销全部分区。Spring Kafka 没有改动客户端的这两个默认值。
2. **切换到新协议**：只加 `spring.kafka.consumer.properties[group.protocol]=consumer`，容器照常消费；`group.remote.assignor` 为 `null`，由服务端的默认分配器决定。
3. **需要删掉的旧配置**：新协议下保留 `session.timeout.ms`，应用启动失败，根因 `ConfigException: session.timeout.ms cannot be set when group.protocol=CONSUMER`。`heartbeat.interval.ms` 与 `partition.assignment.strategy` 同理（见原生客户端实验）。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。JDK 21.0.5，Spring Boot 4.1.1、Spring Kafka 4.1.1；客户端 `kafka-clients` 4.2.1（Spring Boot 4.1.1 管理的版本），broker 4.3.1。

## 三、执行步骤

`mvn test`：三个测试共用一个 Kafka 容器。前两个测试各启动一次 Spring 容器、发送一条消息并等待监听器收到；第三个测试用 `SpringApplicationBuilder` 启动应用并捕获启动失败的根因。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证了配置与能否消费，没有在 Spring Kafka 容器下重复扩容停顿的测量；停顿的对比见原生客户端实验。
- 单节点 Kafka，只用于确认配置行为。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过 | 补充 Spring Kafka 下的默认值与迁移步骤 |
