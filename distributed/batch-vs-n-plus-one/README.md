# 分两次查：N+1 与批量查询

对应文章：[cross-database-join.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/cross-database-join.md)。

单文件程序 `src/BatchJoin.java`，连接共享的 MySQL 8.4.11。1,000 条订单涉及 200 个用户，给每条订单补上用户名：

1. N+1：每条订单查一次用户，1,000 次查询；
2. 先对用户 ID 去重，再逐个查询，200 次；
3. 去重后一次 `IN` 批量查询；
4. 同库 `JOIN`，作为「两张表在同一个库」时的对照。

每种写法分别走两条连接：直连（容器共享网络，回环地址，往返接近 0），以及经过程序内一个每个方向延迟约 0.5 ms 的 TCP 代理（约 1 ms 往返，接近同机房跨主机）。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、网络可访问 Maven Central（首次下载 Connector/J）；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
