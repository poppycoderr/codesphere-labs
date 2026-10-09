# 按业务键保序在哪里会断

对应文章：[kafka-key-ordering.md](https://github.com/poppycoderr/codesphere/blob/master/docs/messaging/kafka-key-ordering.md)。

`scripts/verify.sh` 用 compose 启动一个单节点 Kafka 4.3.1（KRaft），`src/Ordering.java` 用 kafka-clients 4.3.1 连接它，依次执行四组场景。消息的值形如 `order-7:v3`，表示订单 7 的第 3 个版本；「状态」是进程内的一个 Map，保存每个订单最后一次被应用的版本。

1. 主题 4 个分区，为 20 个订单各写入 v1、v2；把分区数增加到 8，再各写入 v3。统计换了分区的订单；取其中一个，先消费新分区再消费旧分区，看最后留下哪个版本；检查每个分区内部版本是否递增。
2. 单分区主题里两个订单各 6 个版本：整批交给 4 个线程的池（奇数版本处理得慢），与按订单号分到 4 条各自串行的通道。
3. v1 处理失败转入重试主题，v2 处理成功，之后重试 v1：直接覆盖与只接受更大的版本。
4. 8 个分区下，20 个订单号按 murmur2 取模与按 CRC32 取模得到的分区；并核对 murmur2 的计算与实际写入的分区一致。

键到分区的映射由哈希决定，其余场景按固定顺序构造，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。集群在脚本结束时删除。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence
make clean
```
