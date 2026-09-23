# 百万行导入：六种 JDBC 写入方式与批处理失败语义

对应文章：[mysql-bulk-import.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-bulk-import.md)。

默认配置（开启 binlog）的 MySQL 上，用 JDBC 对比逐行自动提交、批处理（是否开启 `rewriteBatchedStatements`、批大小、4 个线程）与 `LOAD DATA LOCAL INFILE` 的写入速度，每种方式采样 3 次；再观察一批 6 行中有 1 行主键冲突时，两种驱动设置下 `getUpdateCounts()` 与提交后的数据。

**轻量实验**：保留核心脚本、一条验证入口、原始输出和简要验证记录。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、网络可访问 Maven Central（首次下载 Connector/J）；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
