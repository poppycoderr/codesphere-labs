# 典型业务功能的几个实测

对应文章：[feature-design.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/feature-design.md)。

单节点 Redis 8.10.1（`compose.yaml`），客户端 `src/Features.java`（最小的 RESP2 客户端，无第三方依赖）：

1. ZSET 同分时的排序，以及 `得分 × 10^11 + (10^11 − 提交时间后 11 位)` 的分数编码；
2. 100 万成员的 ZSET：编码与 `MEMORY USAGE`；容器内用 `redis-benchmark`（50 个连接、各 10 万次）测取前 10 名、按名次翻到第 50 万名、按分数带 `LIMIT 500000` 偏移、`ZREVRANK` 的吞吐，并用 `SLOWLOG` 记录单条命令的执行耗时；
3. `GEOADD` 三家店，`GEOSEARCH` 按 1 公里、10 公里查询；
4. 二倍均值法把 100 元拆给 10 人，模拟 10 万轮（纯 Java）。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 1 分钟（不含拉取镜像）
make evidence
make clean
```
