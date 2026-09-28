# Redis ZSET 延时队列

对应文章：[message-queue.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/message-queue.md)。

单文件程序 `src/DelayQueue.java`（内含最小的 RESP2 客户端，无第三方依赖），连接单节点 Redis 8.10.1：

1. **触发延迟**：1,000 个任务，到期时间分布在 0.5—2.5 秒后，或者全部在 1 秒后同时到期；一个消费者按固定间隔轮询，用 Lua 脚本取出并删除到期任务，改变轮询间隔（20、100、500 ms）与每次最多取出的数量（100、1,000）；
2. **重复消费**：1,000 个已到期任务，4 个消费者同时抢，比较「`ZRANGEBYSCORE` 后 `ZREM`，不看返回值」「只处理 `ZREM` 返回 1 的」与 Lua 脚本取删；
3. **取出后崩溃**：100 个任务，第一个消费者取出 10 个后不再处理，比较直接取删与「移入处理中集合 + 租约过期回收」。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 30 秒（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器
```
