# RST 从哪来

对应文章：[tcp-reset-sources.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/tcp-reset-sources.md)。

`compose.yaml` 启动两个 netshoot 容器：`srv`（172.31.10.10）用 `src/rst.py serve` 在 9001—9006 端口跑六种关闭方式的服务端，`cli`（172.31.10.20）运行客户端子命令，同时用 tcpdump 抓整个过程，最后按端口和标志位统计服务端发出的 RST 与 FIN。客户端容器的 NET_RAW、NET_ADMIN 只用于在自己的命名空间里抓包。

```bash
make verify     # 需要 Docker；约 30 秒（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
