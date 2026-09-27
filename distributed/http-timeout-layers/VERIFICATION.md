# 验证记录：一次 HTTP 调用的超时分层

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 21.0.12 与 25.0.4 结果相同（`connectTimeout` 1 秒，请求 `timeout` 2 秒，服务端每个阶段卡 5 秒）：

| 卡住的阶段 | 结果 | 耗时 |
|---|---|---|
| 端口没有监听 | `ConnectException` | 约 20 ms |
| SYN 没有回应 | `HttpConnectTimeoutException` | 约 1 秒 |
| TCP 已连上，TLS 握手没有回应 | `HttpConnectTimeoutException` | 约 1 秒 |
| 同上，不设请求 `timeout` | `HttpConnectTimeoutException` | 约 1 秒 |
| 响应头迟迟不发 | `HttpTimeoutException` | 约 2 秒 |
| 同上，不设请求 `timeout` | 等到服务端响应，HTTP 200 | 约 5 秒 |
| 响应头已发出，响应体发到一半停住 | 请求 `timeout` 没有触发，等到 HTTP 200 | 约 5 秒 |
| 同上，`sendAsync()` 加 3 秒截止时间并 `cancel(true)` | 按时返回 | 约 3 秒 |

结论：

1. `connectTimeout` 覆盖 TCP 建连和 TLS 握手（HTTPS 下，一个「连接」要握手完成才算建立）。
2. 请求 `timeout` 只覆盖到收到响应头；响应体的读取不受它约束。需要限制整次调用时，要在外面再加一个截止时间。
3. 服务端已经提交、响应却超时：客户端看到的是 `HttpTimeoutException`，无法区分「没执行」和「执行了但没回来」。等 1.5 秒后重试一次：不带幂等键时服务端预留了 2 次；带 `Idempotency-Key` 时，重试直接拿到第一次的结果，只预留 1 次。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。Docker 中的 eclipse-temurin 21.0.12 与 25.0.4（固定 digest），HTTP/1.1，TLS 使用启动时生成的自签 EC 证书。

## 三、执行步骤

`scripts/verify.sh` 在两个镜像中各运行一次 `java /src/Timeouts.java`，逐项断言异常类型和耗时范围。每次调用用 `sendAsync()`，外层默认 8 秒截止时间兜底，防止测试挂住。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 只验证了 HTTP/1.1。HTTP/2 下多个请求共用一条连接，连接建立只发生一次，`connectTimeout` 只对第一次请求有意义。
- 「SYN 没有回应」用占满接收队列的方式制造，依赖 Linux 的默认行为（`tcp_abort_on_overflow=0`）；真实网络中的丢包、防火墙丢弃效果相同，但重传间隔可能不同。
- `cancel(true)` 之后客户端按时返回；服务端这一侧的连接何时关闭没有单独验证。
- 幂等键的去重在本实验里用内存 Map 实现，只说明语义；生产实现要和业务写入放在同一个事务里。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
