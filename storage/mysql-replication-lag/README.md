# 复制延迟：稳态、大事务、并行回放、replica 查询负载与链路静默中断

对应文章：[mysql-replication-and-replica-lag.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-replication-and-replica-lag.md)。

1 个 source 加 2 个 replica（GTID、行格式、默认并行回放参数）。source 上每 50ms 写一条带序号的心跳，同时每 250ms 采样两个 replica 的业务水位、`Seconds_Behind_Source`、接收与应用状态；覆盖大事务、独立行与热点行下的并行回放、replica 上的重查询，以及把 replica 从复制网络静默断开。

按验证标准的十个部分记录，原始时间线与对照数据全部归档。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、网络可访问 Maven Central（首次下载 Connector/J）；约 4 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
