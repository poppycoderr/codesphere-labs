# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `probe.tsv` | `sent_at_ms`、`latency_us`（往返）、`delay_since_due_us`（相对计划时刻的推迟，含客户端排队） |
| `phases.tsv` | 阶段开始时刻（容器时钟）：baseline、load、del、unlink、expiry_same、expiry_jitter、lua、keys、fork、slow_subscriber、end |
| `slowlog.json` | `redis-cli --json SLOWLOG GET 256`：id、时间戳（秒）、耗时（微秒）、参数、客户端地址、客户端名 |
| `latency-latest.json`、`latency-history.txt`、`latency-doctor.txt` | `LATENCY LATEST`（事件、最近时刻、最近毫秒、最大毫秒）、每类事件的历史、`LATENCY DOCTOR` |
| `slow-subscriber.tsv` | 容器内每 0.2 秒采样的客户端数、订阅客户端数、`mem_clients_normal`、`client_recent_max_output_buffer`、断开次数 |
| `pipeline.tsv`、`pipeline.log` | 批次、命令数、耗时、吞吐、单批往返 p50 与 p99 |
| `intrinsic-latency.txt` | `redis-cli --intrinsic-latency 5` 的最后几行 |
| `config.tsv`、`info-commandstats.txt`、`info-end.txt` | 相关配置与结束时的 INFO |
| `probe.log` | 探测客户端的标准输出 |
| `assertions.txt` | 各阶段的分位数与断言结果 |
| `container.txt`、`environment.txt` | 镜像、资源限制与运行环境 |

规范化：无。
