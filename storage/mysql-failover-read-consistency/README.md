# 故障切换与读一致性：异步、半同步、超时退化、重试歧义、写后读与旧 source 接回

对应文章：[mysql-failover-and-read-consistency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-failover-and-read-consistency.md)。

客户端持续写入带唯一 `request_id` 的订单并记录每一笔确认；分别在异步复制分区后、半同步且 replica 暂停应用时、半同步超时退化后让 source 崩溃，按「判定 → 比较候选 → 应用 relay log → 提升 → 切换路由」切换，对照确认序列统计缺失与重复。另含半同步等待期间的客户端超时重试、四种写后读策略和旧 source 直接接回。

按验证标准的十个部分记录，原始时间线与对照数据全部归档。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、网络可访问 Maven Central（首次下载 Connector/J）；约 8—10 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
