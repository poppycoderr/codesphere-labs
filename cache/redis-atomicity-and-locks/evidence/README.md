# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `main.tsv` | `步骤\t结果`：`quota.<写法>.*` 为名额扣减，`tx.*` 为事务，`lock.*` 为锁，`lease.<fenced/unfenced>.*` 为租约场景（`timeline` 为相对场景开始的毫秒时间线） |
| `before-restart.tsv`、`after-restart.tsv` | 重启前后的 `EVALSHA` 与 `FCALL` 结果 |
| `main.log`、`before-restart.log`、`after-restart.log` | 程序标准输出 |
| `server.txt` | Redis 版本 |
| `assertions.txt` | 断言结果 |
| `container.txt`、`environment.txt` | 镜像、资源限制与运行环境 |

规范化：无。
