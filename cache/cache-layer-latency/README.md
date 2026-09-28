# 缓存分层的访问延迟

对应文章：[cache-design-and-redis-ops.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/cache-design-and-redis-ops.md)。

`compose.yaml` 启动 Redis 8.10.1 与 MySQL 8.4.11，客户端 `src/Layers.java` 运行在接入同一网络的 JDK 21 容器里，按主键读一条商品数据（1 万个 key）：

1. Caffeine 3.3.0（`maximumSize(10_000)`、`expireAfterWrite(30s)`），全部命中，1,000 万次取平均，5 轮取最快；
2. Redis `GET`，单连接顺序请求 20,000 次（前 2,000 次预热不计）；
3. MySQL 主键查询（服务端预处理语句），单连接顺序请求 20,000 次（前 2,000 次预热不计）。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 1 分钟（不含拉取镜像）
make evidence
make clean
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
