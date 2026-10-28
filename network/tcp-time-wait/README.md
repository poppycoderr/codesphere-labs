# TIME_WAIT 与端口复用

对应文章：[tcp-time-wait.md](https://github.com/poppycoderr/codesphere/blob/master/docs/network/tcp-time-wait.md)。

`compose.yaml` 启动两个 netshoot 容器，各自一个网络命名空间：`srv`（172.31.9.10）跑服务端，`cli`（172.31.9.20）跑客户端，`cli` 的临时端口范围用 `sysctls` 缩小为 40000—40009。`src/tcp.py` 提供监听、连接、绑定、REUSEPORT、连续建连几个子命令，`scripts/verify.sh` 按场景在两个容器里执行，并用 `ss` 统计 TIME_WAIT。不需要特权，只改容器自己命名空间里的设置。

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
