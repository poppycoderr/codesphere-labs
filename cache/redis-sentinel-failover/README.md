# Redis Sentinel 故障切换

对应文章：[redis-replication-sentinel-and-failover.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-replication-sentinel-and-failover.md)。

1 个 primary、2 个 replica、3 个 Sentinel（quorum 2，`down-after-milliseconds 3000`），Redis 8.10.1。两个网络：`data` 承载复制与 Sentinel 探测，`side` 给客户端使用，所以把旧 primary 从 `data` 断开后，客户端仍能写它，重现「客户端和旧 primary 在分区同一侧」的情况。

客户端 `src/Failover.java`（自带最小 RESP 客户端）每 200ms 询问一次 Sentinel，每秒写入 200 条带序号的 key，记录每一次写入的结果；另一个线程订阅 Sentinel 的全部事件。场景：

- replica 暂停后重连：1 MB 与 16 KB backlog 下的部分同步与全量同步；
- primary 进程被 `SIGKILL`，之后重启的旧 primary 被改为 replica；
- primary 网络分区：异步复制、`min-replicas-to-write 1` + `min-replicas-max-lag 2`、每次写入后 `WAIT 1 100`。

每个故障场景重建拓扑，最后导出最终 primary 上的全部序号，与客户端确认的序号比对。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）与 Python 3；约 6 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与网络
```

镜像固定 digest，容器不暴露宿主机端口。
