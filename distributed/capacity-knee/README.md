# 容量拐点

对应文章：[high-concurrency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/high-concurrency.md)。

1. `src/Capacity.java`：10 万行账户表，每个线程一条连接闭环压测（发出、等结果、再发下一个），主键点查的并发从 1 加到 256，主键更新的并发从 1 加到 64；每档预热 3 秒、测 8 秒，记录吞吐、p50、p99；
2. 另起一个同样限制 2 CPU 的 Redis 8.10.1 容器，在容器内用 `redis-benchmark`（50 个连接）测 `SET`、`GET` 的吞吐。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、网络可访问 Maven Central（首次下载 Connector/J）；约 3 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
