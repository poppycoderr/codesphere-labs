# Redis 内存满了会怎样

对应文章：[redis-memory-and-eviction.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-memory-and-eviction.md)。

单节点 Redis 8.10.1，关闭持久化。覆盖 `maxmemory 8mb` 下五种配置的写入结果、一次性扫描对 LRU 与 LFU 的影响、20 万个 key 同时过期与分散过期的回收时间序列、过期后的碎片率，以及删除四分之三数据后打开主动碎片整理的效果。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）与 Python 3；约 2 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器
```

镜像固定 digest，容器不暴露宿主机端口。
