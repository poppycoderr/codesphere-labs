# WebSocket 接口测试

对应文章：[websocket-testing.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/websocket-testing.md)。

`src/lab.py` 在固定 digest 的 `python:3.14.8-slim` 容器里运行，`websockets==17.2` 从 PyPI 安装（缓存在仓库的 `.cache/pip`）。同一个进程里起本地 WebSocket 服务：`/plain` 回显或按指定延迟回复，`/push` 在连接建立后先推送一条通知，`/secure` 在握手阶段检查 `Authorization` 头；服务端还能按指令以 1008 关闭、直接断开 TCP 或不回复，并记录打开的连接数。客户端依次验证一发一收、按 id 分派、并发 recv、超时、鉴权、关闭码、消息大小与连接清理。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
