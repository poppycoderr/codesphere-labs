# Redis 延迟诊断

对应文章：[redis-command-execution-and-latency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-command-execution-and-latency.md)。

单节点 Redis 8.10.1（2 CPU、2 GB，关闭持久化，`latency-monitor-threshold 1`）。探测客户端 `src/Latency.java` 每毫秒发一次 `GET`，记录往返耗时与相对计划时刻的推迟；同时依次注入：

- `DEL` 与 `UNLINK` 百万成员的 Set；
- 50 万个 key 集中过期，与分散到 10 秒的过期；
- Lua 空循环与 `KEYS` 遍历 100 万个 key；
- 由 save 规则触发的 `BGSAVE`（fork）；
- 订阅后不读取的慢订阅者，同时发布 3 万条 4 KB 消息。

最后对比 SLOWLOG、LATENCY 与探测延迟，并测 pipeline 每批 1、10、100、1000 条 `SET` 的吞吐与单批往返。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）与 Python 3；约 3 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器
```

镜像固定 digest，容器不暴露宿主机端口。
