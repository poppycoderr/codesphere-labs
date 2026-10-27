# 请求结果归谁

对应文章：[request-result-ownership.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/request-result-ownership.md)。

`src/run.mjs` 在同一个进程里起一个 HTTP 服务（返回 404、500、204 与按参数延迟的搜索结果），用 Node 内置的 fetch 观察错误语义、回调适配、输入联想里的迟到响应，以及超时与取消。

```bash
make verify     # 需要 Docker；约 5 秒
make evidence   # 重新采集 evidence/
make clean
```
