# 流式响应的生命周期

对应文章：[streaming-response-lifecycle.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/streaming-response-lifecycle.md)。

`src/lab.py` 在固定 digest 的 `python:3.14.8-slim` 容器里运行，`fastapi==0.156.2`、`uvicorn==0.54.0`、`httpx==0.28.1` 从 PyPI 安装（缓存在仓库的 `.cache/pip`）。进程内启动 uvicorn，只监听容器内回环；生成器每 50 毫秒吐出一个假 token，共 20 个，并记录已生成的数量、是否执行了 `finally`、是否收到 `CancelledError`。客户端用 httpx 流式读取：按 SSE 规则解析一组故意少了空行的帧；分别从异步生成器和同步生成器读 3 个 token 后断开，等 1.5 秒再看服务端的计数；最后读取一个生成到一半抛异常的流，以及一个把失败写成 `event: error` 的流。

## 快速运行

```bash
make verify     # 需要 Docker；约 15 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
