# Kafka 的吞吐从哪里来

对应文章：[why-kafka-is-fast.md](https://github.com/poppycoderr/codesphere/blob/master/docs/messaging/why-kafka-is-fast.md)。

三节点 KRaft 集群（`compose.yaml`，Kafka 4.3.1，固定 digest，每个节点 1.5 CPU、1.5 GB），客户端在另一个 2 CPU 的容器里运行（同一镜像）。五组实验：

1. **批处理**：同一个 topic（1 分区、1 副本），`batch.size=0`、默认值、`batch.size=262144 linger.ms=20` 三组，各 20 万条 256 字节，3 轮；
2. **压缩**：`none`、`lz4`、`zstd` 各一个 topic，消息体是重复字段较多的 JSON，比较磁盘上的日志大小；
3. **确认级别**：3 副本、`min.insync.replicas=2`，`acks=0/1/all` 在固定 2 万条/秒下的延迟，以及不限速时的吞吐；
4. **分区并行**：`src/Partitions.java`，3000 条、每条处理 2ms，6 个消费者分别消费 1 个分区和 6 个分区；
5. **热点 key**：6000 条分到 6 个分区，key 均匀分布与 80% 同一个 key 对比。

1—3 用镜像自带的 `kafka-producer-perf-test.sh`；4—5 的程序用 `kafka-clients` 4.3.1 编译，在客户端容器里运行。

## 快速运行

```bash
make verify     # 需要 Docker、JDK 21（javac）、Python 3；约 5 分钟
make evidence
make clean      # 删除集群
```
