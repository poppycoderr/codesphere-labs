# 一次 HTTP 调用的超时分层

对应文章：[timeout-layers.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/timeout-layers.md)。

单文件程序 `src/Timeouts.java`，服务端与客户端在同一个容器里运行：

| 端口 | 服务端行为 |
|---|---|
| 9443 | 可控的 HTTPS 服务（自签证书，启动时用 `keytool` 生成）：`/slow-headers` 5 秒后才发响应头；`/slow-body` 先发响应头和一半响应体，5 秒后再发另一半；`/reserve` 先预留名额、3 秒后才响应，按 `Idempotency-Key` 去重 |
| 9444 | 接受 TCP 连接，但从不回应 TLS 握手 |
| 9445、9447 | backlog 为 1 且从不 `accept`，接收队列被占满后 Linux 会丢弃新的 SYN（每个场景各用一个端口） |
| 9446 | 没有监听 |

客户端是 JDK `HttpClient`（HTTP/1.1），`connectTimeout` 1 秒，请求 `timeout` 2 秒。JDK 21.0.12 与 25.0.4 各跑一遍。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
