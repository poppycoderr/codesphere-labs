# 用 requests 写接口测试

对应文章：[requests-api-testing.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/requests-api-testing.md)。

`src/lab.py` 在固定 digest 的 `python:3.14.8-slim` 容器里运行，`requests==2.34.2` 从 PyPI 安装（缓存在仓库的 `.cache/pip`）。同一个进程里用 `ThreadingHTTPServer` 起一个本地服务，回显收到的查询参数、Content-Type 与请求体，另有 3 秒才响应的 `/slow`、返回 500 的 `/fail`、设置 Cookie 的 `/login` 与检查 Cookie 的 `/me`；服务端记录客户端端口，用来统计 TCP 连接数。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
