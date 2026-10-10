# 监听队列

对应文章：[accept-backlog.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/accept-backlog.md)。

`src/backlog.py` 在固定 digest 的 python 3.14 容器里运行，只用容器自己网络命名空间里的回环地址。服务端 `listen` 之后一直不调用 `accept`，模拟应用线程全部被占住、来不及取走新连接的情况。客户端同时发起 40 个非阻塞连接，2 秒后统计握手完成的个数；再在一个握手完成的连接上发请求；再用 1 秒的连接超时发起一个新连接；同时读取 `/proc/net/netstat` 里的 `ListenOverflows` 与 `ListenDrops`。

运行两次：`listen(5)`、默认内核参数；`listen(1000)`、用 `docker run --sysctl` 把这个容器的 `net.core.somaxconn` 设成 8。

`src/DefaultBacklog.java` 在 temurin 25 容器里验证 `new ServerSocket(port)` 不指定 backlog 时能完成多少个握手。

## 快速运行

```bash
make verify     # 需要 Docker；约 40 秒
make evidence
make clean
```
