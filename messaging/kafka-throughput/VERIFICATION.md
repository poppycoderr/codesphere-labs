# 验证记录：Kafka 的吞吐从哪里来

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **批处理是吞吐的主要来源**（20 万条 256 字节，`acks=1`，3 轮中位数）：
   - `batch.size=0`：约 9450 条/秒，每个请求 1.0 条；
   - 默认（`batch.size=16384`，`linger.ms=5`）：约 18.2 万条/秒，每个请求约 61 条，批大小约 16 KB；
   - `batch.size=262144`，`linger.ms=20`：约 36.6 万条/秒，每个请求约 980 条。
   关闭批处理后，平均延迟反而高达数秒：请求一条一条发，发送缓冲区里排起了长队。
2. **压缩主要省磁盘与网络**：同样 20 万条 JSON，磁盘上的日志不压缩 31.0 MB、`lz4` 7.5 MB、`zstd` 4.2 MB。吞吐只跑了一轮，波动大（这一轮 `none` 约 36.4 万条/秒、`lz4` 约 18.0 万、`zstd` 约 33.4 万；另一次运行中 `lz4` 高于 `none`），不据此比较速度。
3. **确认级别是用延迟换可靠**（3 副本，`min.insync.replicas=2`）：
   - 固定 2 万条/秒：`acks=0` 平均 2.1ms、p99 12ms；`acks=1` 平均 3.4ms、p99 33ms；`acks=all` 平均 12.4ms、p99 183ms；
   - 不限速：`acks=0` 约 18.7 万条/秒、`acks=1` 约 17.3 万、`acks=all` 约 7.8 万。
4. **分区决定消费并行度**：3000 条、每条处理 2ms、6 个消费者：1 个分区时只有 1 个消费者在处理，7.1 秒；6 个分区时 6 个消费者都参与，1.2 秒。
5. **热点 key 把并行度打回一个分区**：6000 条、每条处理 1ms、6 个消费者：key 均匀分布时最大分区占 17%，1.2 秒；80% 的消息用同一个 key 时，一个分区分到 4976 条（83%），5.9 秒。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)。Kafka 4.3.1（KRaft，三个节点同时是 broker 与 controller），Docker Desktop 虚拟机 6 核、16 GB。

## 三、执行步骤

`scripts/verify.sh` 启动集群，在单独的客户端容器里依次运行三组 `kafka-producer-perf-test.sh`（`--print-metrics` 输出生产者指标），压缩组结束后在各 broker 上统计该分区 `.log` 文件的总字节数；最后编译并运行 `src/Partitions.java`。分区并行与热点 key 两组在 6 个消费者都分到分区之后才开始生产，测量从第一条被处理到最后一条被处理的时间。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/summary.tsv` | 汇总 |
| `evidence/perf.tsv` | 压测原始输出与生产者指标 |
| `evidence/partitions.tsv` | 分区并行与热点 key |
| `evidence/container.txt` | 镜像与资源 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`（只断言数量关系：批处理吞吐依次上升、压缩后磁盘依次变小、`acks=all` 延迟最高、分区与热点 key 的耗时关系）。

## 五、误差、限制与不能推出的结论

- 三个 broker 和客户端在同一台机器的容器里，网络是虚拟网桥，没有跨机房延迟；绝对数字不能外推到生产集群。
- 没有测量零拷贝：客户端和 broker 之间没有 TLS，也没有对比开启 TLS 的情况；文章中关于零拷贝的说法来自文档。
- 页缓存的作用没有单独测量：消费的数据都是刚写入的，大概率仍在页缓存中。
- 分区并行实验里的「处理」是 `Thread.sleep`，代表 I/O 型的处理；CPU 型的处理还会受客户端容器 CPU 数量限制。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-26 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
