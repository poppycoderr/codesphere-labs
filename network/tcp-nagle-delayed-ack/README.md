# 小包为什么等了 40 毫秒

对应文章：[tcp-nagle-delayed-ack.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/tcp-nagle-delayed-ack.md)。

`compose.yaml` 启动两个 netshoot 容器：`srv`（172.31.11.10）在 9001—9004 端口跑同样的服务端（读满 20 字节回 10 字节），`cli`（172.31.11.20）按四种写法各发 50 个请求（前 20 个预热不计时），同时用 tcpdump（`--immediate-mode`）抓客户端发出的报文，统计带数据的包数。往返时间只断言范围，原始数值在 `timings.txt`。

```bash
make verify     # 需要 Docker；约 30 秒（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
