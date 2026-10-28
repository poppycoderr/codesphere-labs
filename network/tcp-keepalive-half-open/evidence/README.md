# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 每个场景的结论行 |
| `partition.txt` | 网络断开时五个连接各自的结果与秒数，第 6 秒仍处于 ESTABLISHED 的连接数 |
| `frozen.txt` | 对端进程冻结时 keepalive 与心跳的结果与秒数 |
| `unauth.txt` | 两种服务端对空闲未认证连接的处理与秒数 |
| `environment.txt` | 运行环境 |
