# 验证记录：Kafka 投递语义的边界

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **事务中止**：读—处理—写的事务在写出 10 条、提交 offset 之后中止：`read_committed` 读到 0 条，`read_uncommitted` 读到 10 条（消息已经写进日志，只是被标记为中止），输入 topic 的已提交 offset 仍为空。重新处理并提交后，`read_committed` 读到 10 条，offset 为 10。
2. **transactional.id 隔离旧实例**：新实例 `initTransactions()` 之后，旧实例未完成的事务被中止：
   - 旧实例继续 `send`：得到 `InvalidProducerEpochException`，随后 `abortTransaction()` 抛出 `ProducerFencedException`；
   - 旧实例直接 `commitTransaction()`：得到 `ProducerFencedException`；
   - 旧实例写入的消息对 `read_committed` 不可见。
3. **自动提交 + 异步处理**：100 条消息交给单线程池处理（每条 50ms），自动提交间隔 100ms；2 秒后进程退出时只处理了 40 条，已提交 offset 已经是 100，重启后 60 条不会再被消费。
4. **min.insync.replicas 与 ISR 收缩**：3 副本、leader 在 1 号 broker，停掉 2、3 号后 ISR 收缩为 `[1]`：
   - `min.insync.replicas=2`：`acks=all` 被拒绝。服务端返回 `NotEnoughReplicasException`（`retries=0` 时直接看到）；默认配置下这是可重试错误，客户端一直重试到 `delivery.timeout.ms`（实验中 5 秒），最终抛出 `TimeoutException`。同一 topic 上 `acks=1` 成功。
   - `min.insync.replicas=1`（默认）：`acks=all` 成功，此时只有 leader 一份数据。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)。Kafka 4.3.1（镜像固定 digest），kafka-clients 4.3.1（镜像自带），`replica.lag.time.max.ms=5000`。

## 三、执行步骤

`scripts/verify.sh` 启动集群，依次运行 `tx`、`fence`、`autocommit`、`isr-setup`、`isr-full`；然后停掉 kafka2、kafka3，运行 `isr-shrunk`（程序先等两个 topic 的 ISR 都收缩到 1 个）。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 自动提交场景中已处理的条数取决于调度，断言只要求「已处理少于 100 条、offset 已提交到 100」。
- 事务中止用 `abortTransaction()` 模拟处理失败，没有模拟进程崩溃；崩溃时未完成的事务由事务协调器在超时后中止，结果对 `read_committed` 相同。
- 只验证了单分区。幂等生产者在 broker 端的序列号去重没有单独构造重试场景。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过 | 需要：被隔离的旧实例不一定先收到 `ProducerFencedException`；`acks=all` 被拒绝时客户端默认看到的是投递超时 |
