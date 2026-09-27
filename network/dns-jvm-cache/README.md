# DNS 切换与 Java 客户端

对应文章：[dns-change-and-java-clients.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/dns-change-and-java-clients.md)。

自建 Docker 网络 `172.29.53.0/24`，不涉及任何真实域名：

| 容器 | 作用 |
|---|---|
| `dns`（172.29.53.53） | CoreDNS，只负责 `lab.test`；记录来自 `build/dns/hosts`，应答 TTL 5 秒，每秒重新加载 |
| `backend1`（.11）、`backend2`（.12） | `src/Backend.java`：响应体是自己的名字；调用 `/drain` 后每个响应都带 `Connection: close` |
| 客户端（临时容器） | `src/Dns.java`：改写记录文件，把 `api.lab.test` 从 backend-1 切到 backend-2，观察 JVM 的解析结果和请求落在哪个后端 |

场景：`resolve`（默认、`networkaddress.cache.ttl` 为 5 和 0）、`negative`（负缓存）、`pool`（JDK `HttpClient`）、`pool-urlconnection`（`HttpURLConnection`）。

## 快速运行

```bash
make verify     # 需要 Docker；约 3 分钟
make evidence
make clean      # 删除容器与网络
```
