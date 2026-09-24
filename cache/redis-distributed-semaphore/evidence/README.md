# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `results.tsv` | `步骤\t结果`：返回值与账目（available = LLEN permits，holders = ZCARD holders）、压力统计、`lease.timeline` 为相对程序启动的毫秒时间线 |
| `run.log` | 程序标准输出 |
| `assertions.txt` | 断言结果 |
| `container.txt`、`environment.txt` | 镜像、资源限制与运行环境 |

规范化：无。
