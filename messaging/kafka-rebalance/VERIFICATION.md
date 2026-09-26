# 验证记录：Kafka 消费组重平衡

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **客户端默认值**：kafka-clients 4.3.1 的 `group.protocol` 默认 `classic`，`partition.assignment.strategy` 默认 `[RangeAssignor, CooperativeStickyAssignor]`。新协议下设置 `session.timeout.ms` 抛出 `ConfigException`。
2. **成员都健康时扩容**（两轮）：

| 方式 | 被撤销的分区 | 所有者没变的分区最长中断 | 换了所有者的分区最长中断 |
|---|---:|---|---|
| eager | 6 | 137～141 ms | 131～148 ms |
| 协作式 | 2 | 152～171 ms | 3,388～3,407 ms |
| 新协议 | 2 | 150～162 ms | 595～649 ms |

3. **有一个成员在处理 8 秒的慢批次时扩容**（两轮）：

| 方式 | C1 保留的分区最长中断 | C1 交出分区 | 扩容后首次分配 |
|---|---|---|---|
| eager | 7,698～8,627 ms | 慢处理结束之前（立刻交出全部） | 慢处理结束之后 |
| 协作式 | 125～145 ms | 慢处理结束之后 | 慢处理结束之后 |
| 新协议 | 129～155 ms | 慢处理结束之前 | 慢处理结束之前 |

经典协议下，慢成员处理期间组里没有完成任何重新分配；新协议下，健康成员在慢成员还在处理时就交出了要移走的分区，新成员在下一次心跳时接手。

4. **处理超时导致的重复**：经典协议下，C1 第 3 批处理 8 秒，超过 `max.poll.interval.ms` 被移出组，收到 `onPartitionsLost`，提交抛出 `CommitFailedException`，这一批 100 条被 C2 再处理了一遍。新协议下同样的程序这次没有重复（C1 被移出后很快重新入组），不能当作保证。
5. **静态成员身份**：C1 关闭、3 秒后用同一个 `group.instance.id` 重启，两种协议下 C2 都没有任何撤销与分配，C1 约 50ms 后拿回原来的分区；动态成员时，经典协议下 C2 被撤销 2 次、分配 2 次，新协议下各 1 次，C1 约 3 秒（经典）、5 秒（新协议）后才拿回分区。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)。Kafka 4.3.1（镜像固定 digest），kafka-clients 4.3.1（镜像自带），三节点 KRaft；消费组相关的服务端配置均为默认值（新协议的心跳间隔 `group.consumer.heartbeat.interval.ms` 为 5 秒）。

## 三、执行步骤

`scripts/verify.sh` 启动集群，依次运行 `defaults`、`config`；扩容场景每种方式两轮；`dup`、`static`、`dynamic` 在经典 eager 与新协议下各一次。扩容前先等组稳定（两个成员都分到分区）5 秒；中断只统计从扩容前 0.5 秒开始的部分。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/check.py`。

## 五、误差、限制与不能推出的结论

- 新协议下，被移动分区的停顿取决于新成员的心跳落在 5 秒周期的什么位置。本次两轮是 0.6 秒左右，调试时的其他运行中出现过 0.5～5.2 秒。
- 开始时曾把「组还没稳定」的时段算进了扩容停顿（新协议下第二个成员入组要几秒），修正后才得到上表；文章早先引用的单节点数据可能受同样的影响。
- 实验结束时关闭消费者产生的撤销不计入扩容结果。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立（三节点），全部断言通过 | 是：新协议下健康成员保留的分区几乎不停（原文 1.2～1.7 秒）；被移动的分区停顿取决于心跳周期 |
