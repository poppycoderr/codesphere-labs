# 对端还活着吗

对应文章：[tcp-keepalive-half-open.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/tcp-keepalive-half-open.md)。

`compose.yaml` 启动两个 netshoot 容器：`srv`（172.31.12.10）用 `src/ka.py serve` 跑一问一答的服务端、第一次回应后冻结自己（SIGSTOP）的服务端、没有首包超时与有 2 秒首包超时的服务端；`cli`（172.31.12.20）先建立五个连接，再用 iptables 在自己的命名空间里丢弃与服务端之间的双向报文，并行观察它们 10 秒内的结果。时间只断言范围，原始秒数另存。

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
