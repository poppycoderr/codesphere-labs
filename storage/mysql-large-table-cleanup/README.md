# 大表清理：一次性删除、锁范围、分批删除、主键区间、删分区与空间回收

对应文章：[mysql-large-table-cleanup.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-large-table-cleanup.md)。

300 万行事件表的四份副本上，对比一次性删除与分批删除的耗时、事务数和 binlog 量，探测删除事务持有期间的锁范围（含优化器放弃索引的情形），验证主键区间法的空批次、`DROP PARTITION` 的 binlog 量，以及删除与 `OPTIMIZE TABLE` 前后的文件大小。

**轻量实验**：保留核心脚本、一条验证入口、原始输出和简要验证记录。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 3—4 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
