# 验证记录：请求失败在客户端的样子

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | JDK HttpClient（连接超时 2 秒） | Socket.connect（超时 10 秒） |
|---|---|---|
| 正常 | HTTP 200，< 50 ms | 连接成功 |
| 服务返回 503 | HTTP 503，< 50 ms（状态码，不是异常） | 连接成功 |
| 域名不存在 | `ConnectException ← UnresolvedAddressException`，< 50 ms | `UnknownHostException`，< 50 ms |
| 目标网段是 unreachable 路由 | `ConnectException(No route to host) ← NoRouteToHostException`，< 50 ms | `NoRouteToHostException`，< 50 ms |
| 同网段不存在的主机（邻居解析失败） | `HttpConnectTimeoutException`，约 2.0 秒 | `NoRouteToHostException`，约 3.1 秒 |
| 端口无人监听 | `ConnectException ← ClosedChannelException`，< 50 ms | `ConnectException(Connection refused)`，< 50 ms |
| SYN 被防火墙丢弃 | `HttpConnectTimeoutException`，约 2.0 秒 | `SocketTimeoutException(Connect timed out)`，约 10.0 秒 |

- 邻居解析失败时内核约 3 秒后返回「No route to host」；HttpClient 的连接超时是 2 秒，先到，于是它和 SYN 被丢弃在 HttpClient 里看起来一模一样，要看 `ip neigh`：两个不存在的地址都是 `FAILED`。
- HttpClient 在端口无人监听时，异常链里没有「Connection refused」字样；Socket 有。
- `ping` 服务端 2 个包全部收到，同时 `curl` 连 9090 端口失败：能 ping 通不代表服务在监听。
- `ip route get 10.201.0.5` 返回 `RTNETLINK answers: Host is unreachable`；`ip route get 172.31.7.99` 显示直连路由——路由存在，失败在邻居解析这一步。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。JDK 21 容器，netshoot v0.16（iproute2 7.1.0）。

## 三、执行步骤

见 `scripts/verify.sh`。邻居解析失败的 Socket 探测用另一个没有访问过的地址（172.31.7.98），避免邻居表里已有 `FAILED` 记录让它提前返回（调试时同一地址第二次探测只用了 1.1 秒）。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 异常类型与消息来自 JDK 21 的 HttpClient 与 Linux 内核（Docker Desktop 的虚拟机）；其他 JDK 版本、HTTP 客户端库或操作系统可能不同，这里只说明同一个客户端里几类失败的区别。
- 邻居解析失败返回的时间取决于内核的 ARP 重试参数。
- 没有覆盖 TLS 握手失败、读超时与服务端已处理但响应丢失的情况，这些见超时分层一文的配套实验。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
