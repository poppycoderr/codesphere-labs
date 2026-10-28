# ZooKeeper 会话不是 TCP 连接

对应文章：[zookeeper-session.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/zookeeper-session.md)。

`compose.yaml` 启动固定 digest 的单节点 ZooKeeper 3.9.5；`src/ZkSession.java`（单文件，依赖见 `deps.txt`：Curator 5.9.0、ZooKeeper 客户端 3.9.5 及其 Netty 依赖）在进程内起一个可以「冻结」的 TCP 代理，被测客户端经代理连接，观察者直连。冻结时代理不转发任何数据也不关闭连接，相当于网络中断或进程停顿。依次验证：冻结 3 秒与 12 秒（会话超时 6 秒）、close() 与进程消失、锁持有者失联、一次性 watch 与持久递归 watch。

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
