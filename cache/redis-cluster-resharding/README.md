# Redis Cluster 分片与在线迁移

对应文章：[redis-cluster-sharding-and-resharding.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-cluster-sharding-and-resharding.md)。

6 个 Redis 8.10.1 节点由 `redis-cli --cluster create` 组成 3 primary + 3 replica（`cluster-node-timeout 5000`）。业务负载使用 JedisCluster 5.2.0（`src/ClusterClient.java`，依赖从 Maven Central 下载并固定版本），协议层面的回复用 `redis-cli` 直接观察。场景：

- hash slot、hash tag 与跨 slot 的 `MGET` / `EVAL`；
- 向不负责 slot 的节点请求得到 `MOVED`；
- 手动迁移一个 slot（`SETSLOT IMPORTING / MIGRATING` + 分批 `MIGRATE`），迁移中观察 `ASK` 与 `ASKING`，同时 JedisCluster 持续读写并校验；
- `redis-cli --cluster reshard` 迁移 1000 个 slot，同时持续读写；
- 迁移一个 50 万字段的大 Hash，观察源节点的阻塞；
- 一个 hash tag 固定 5 万个 key 造成的倾斜；
- primary 进程被 `SIGKILL`，replica 提升，JedisCluster 每秒 200 次写入。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、网络可访问 Maven Central（首次下载 Jedis 及其依赖）；约 4 分钟
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器
```

镜像固定 digest，容器不暴露宿主机端口。
