# Redis 数据结构与编码

对应文章：[redis-basics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-basics.md)。

单节点 Redis 8.10.1，关闭持久化。用 `OBJECT ENCODING`、`MEMORY USAGE` 与 `INFO memory` 测量各结构在默认阈值两侧的编码与内存、字符串 `embstr` 的分界、Hash 跨阈值后的内存与「只升不降」、10 万条小对象的两种存法、一百万个 ID 的三种计数结构，以及删除百万元素 Set 时 `DEL` 与 `UNLINK` 的服务端耗时。

数据由 `scripts/gen.py` 按序号生成，经 `redis-cli --pipe` 写入，没有随机数。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）与 Python 3；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器
```

镜像固定 digest，容器不暴露宿主机端口。
