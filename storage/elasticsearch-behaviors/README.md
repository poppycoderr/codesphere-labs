# Elasticsearch 的几个行为

对应文章：[elasticsearch-basics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/elasticsearch-basics.md)、[elasticsearch-aggregation-and-thread-pool.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/elasticsearch-aggregation-and-thread-pool.md)。

单节点 Elasticsearch 9.5.3（`compose.yaml`，2 CPU、2 GB、堆 1 GB，关闭安全认证，不暴露宿主机端口），客户端 `src/EsBehaviors.java` 运行在共享网络的 JDK 21 容器里（Jackson 2.22.3 解析 JSON）：

1. `basics`（默认配置）：`standard` 与 `english` 分析器的分词结果；动态映射、`term` 查 `text` 与 `keyword`、对 `text` 聚合；3 分片索引里 40 个文档的分布与自定义路由；`refresh_interval=30s` 下的写入可见性、`refresh=wait_for` 与 `refresh=true`；21,531 个文档（3 个热门品牌、40 个长尾品牌，固定种子与 `_id`）在 5 分片与 1 分片索引上的 terms 聚合；默认 search 线程池的大小；
2. `pool`（叠加 `compose.pool.yaml`：`thread_pool.search.size=2`、`queue_size=10`）：60 个并发、共 120 个嵌套 terms 聚合请求。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 2 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器
```
