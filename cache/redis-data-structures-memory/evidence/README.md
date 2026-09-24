# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `config.tsv` | 编码阈值、lazyfree、持久化与 maxmemory 的实际值 |
| `server.txt` | Redis 版本、运行模式、分配器 |
| `encodings.tsv` | 阈值两侧的对象：key、编码、`MEMORY USAGE`、说明 |
| `embstr-boundary.tsv` | key 长度、值长度、编码与内存 |
| `hash-crossing.tsv` | Hash 500、600、删回 500 个字段 |
| `buckets.tsv` | 三种存法写入前后的 `used_memory` 与差值 |
| `counting.tsv`、`counting-membership.txt` | Set、Bitmap、HyperLogLog、稀疏 Bitmap；`SMISMEMBER` 抽查 |
| `delete.tsv`、`delete-slowlog-sample.txt` | 每次删除的服务端耗时（微秒）与 SLOWLOG 原文 |
| `assertions.txt` | 断言结果 |
| `container.txt`、`environment.txt` | 镜像、资源限制与运行环境 |

规范化：无。SLOWLOG 原文中的客户端地址是容器内的回环地址。
