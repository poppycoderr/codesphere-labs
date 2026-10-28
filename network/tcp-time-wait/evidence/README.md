# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 每个场景的结论行 |
| `ss-time-wait.txt` | 服务端的 TIME_WAIT 与计时器 |
| `ss-client-time-wait.txt` | 端口耗尽后客户端的 TIME_WAIT |
| `reuseport.txt` | 两个 REUSEPORT 套接字各自接受的连接数（随哈希变化，不参与断言） |
| `kernel.txt` | 内核版本 |
| `environment.txt` | 运行环境 |
