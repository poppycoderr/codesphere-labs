# 基于 Redis 的分布式信号量

对应文章：[redis-concurrency-controller.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-concurrency-controller.md)。

List 存许可、ZSET 记录持有者与租约到期时间，释放与补偿用文章中的两段 Lua。`src/Semaphore.java` 自带一个最小的 RESP 客户端（不依赖第三方库），在与 Redis 共享网络的 JDK 21 容器中运行，覆盖获取、等待超时、释放与重复释放、租约回收、`BRPOP` 与 `ZADD` 之间崩溃、20 个线程的并发压力，以及租约早于任务结束时的超额并发。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）与 Python 3；约 30 秒（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器
```

镜像固定 digest，容器不暴露宿主机端口。
