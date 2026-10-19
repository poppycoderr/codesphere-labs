# 验证记录：WebSocket 接口测试

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| echo 服务一发一收 | 正常 |
| 服务端先推送通知 | 一发一收拿到的是通知，断言失败 |
| 先发慢请求再发快请求 | 第一条收到的是后发的 id=2 |
| 按 id 分派、推送单独入队 | 两个请求各得到自己的结果，推送单独收到 |
| 两个协程同时 recv | `ConcurrencyError` |
| 服务端不回复 | recv 没有超时参数；`asyncio.timeout(1)` 约 1 秒抛 `TimeoutError`，连接仍是 OPEN |
| 握手不带 Token | `InvalidStatus`，HTTP 401，连接没有建立 |
| 握手在请求头带 Token | 连接成功 |
| 正常关闭 | `ConnectionClosedOK`，1000 |
| 服务端 1008 关闭 | `ConnectionClosedError`，1008，原因 `token expired` |
| 发送 2 MiB（服务端上限 1 MiB） | 1009 |
| 服务端直接断开 TCP | `ConnectionClosedError`，没有收到关闭帧 |
| connect 后不关闭 | 服务端仍有 1 个连接；关闭后 0 个 |

客户端默认值：`open_timeout=10`、`ping_interval=20`、`ping_timeout=20`、`close_timeout=10`、`max_size=1048576`（从库的函数签名读取）。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 服务端与客户端在同一个进程里，只用 `ws://` 本地回环，没有验证 `wss://`、代理与负载均衡器对长连接的影响。
- 延迟用 `asyncio.sleep` 构造，乱序依赖 0.3 秒与 0.05 秒的差，容器负载极高时可能改变顺序。
- 只验证 websockets 17.2 的 asyncio 接口；其他语言或库的异常类型与默认值不同。
- 1006 的说法来自 RFC 6455，库在没有收到关闭帧时 `rcvd` 为 None。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-19 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
