# 缓存一致性的窗口

对应文章：[cache-consistency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/cache-consistency.md)。

`compose.yaml` 启动 Redis 8.10.1 与 MySQL 8.4.11，客户端 `src/CacheAside.java` 在接入同一网络的 JDK 21 容器里运行。前四个场景用 `CountDownLatch` 精确控制读、写两个线程的交错顺序，第五个场景不加控制：

1. Cache Aside：读请求未命中、回源读到 100 后停住；写请求更新为 200 并删缓存；读请求再回填；
2. 在事务里删缓存：删除之后、提交之前，读请求回源回填；
3. 延迟 500 ms 双删：读请求在第一次删除后 200 ms 或 800 ms 回填；
4. 写入标记：写入前设置 `product:1:writing`，读请求回填前检查；
5. 读写同时开始、不加控制，2,000 轮，统计结束后缓存仍是旧值的轮数。

另有 `src/GaugeCheck.java`（Micrometer 1.17.1）：比较不一致率指标的两种 gauge 写法。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）；约 1 分钟（不含拉取镜像）
make evidence
make clean
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
