# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `config-defaults.tsv`、`server.txt` | 默认配置、版本与分配器 |
| `policies.tsv` | 五种配置：写入错误数、淘汰数、DBSIZE、带 TTL 的 key、前 1.5 万个 key 的存活数、之后的 SET 与 GET |
| `scan-pollution.tsv` | 扫描污染后的热 key 存活数 |
| `expiry-<场景>-raw.txt` | 容器内采样的 `INFO` 原文，`@@` 后为容器时钟 |
| `expiry-<场景>-load.txt` | 写入起止时间（容器时钟）与 `--pipe` 汇总 |
| `expiry-<场景>.tsv` | 由 `scripts/expiry_tsv.py` 从原文整理的时间序列，t 以开始写入为 0 |
| `memory-after-expiry.txt` | 过期清完后的内存指标 |
| `fragmentation-loaded.txt`、`fragmentation-deleted.txt`、`fragmentation-defragged.txt` | 三个阶段的 `INFO memory` 原文 |
| `activedefrag-set.txt`、`defrag-timeline.tsv`、`fragmentation-dbsize.txt` | 打开主动整理的回复、每秒采样与整理后的 key 数 |
| `assertions.txt` | 断言结果 |
| `container.txt`、`environment.txt` | 镜像、资源限制与运行环境 |

规范化：无。
