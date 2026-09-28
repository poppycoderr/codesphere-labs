# 请求失败在客户端的样子

对应文章：[request-failure-signatures.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/request-failure-signatures.md)。

`compose.yaml` 建一个自有网络 172.31.7.0/24：服务端（JDK HttpServer，8080 端口 `/ok` 返回 200、`/fail` 返回 503，9090 端口无人监听）与一个带 `NET_ADMIN` 的 netshoot 容器。`scripts/verify.sh` 只在 netshoot 自己的网络命名空间里加一条 `unreachable 10.201.0.0/16` 路由和一条丢弃到服务端 8081 端口 SYN 的 iptables 规则，客户端 `src/Client.java` 运行在共享这个命名空间的 JDK 21 容器里。

每个目标用两种方式访问：JDK HttpClient（连接超时 2 秒）与普通 `Socket.connect`（超时 10 秒）。之后记录 `ip route get`、`ip neigh`、`ping` 与 `curl` 的输出。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）；约 1 分钟（不含拉取镜像）
make evidence
make clean
```

不修改宿主机的路由与防火墙，不访问外网。
