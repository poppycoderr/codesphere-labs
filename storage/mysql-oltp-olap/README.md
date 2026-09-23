# OLTP 与 OLAP：同一份 500 万行数据在 MySQL 与 ClickHouse 上的三条查询

对应文章：[mysql-oltp-vs-olap.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-oltp-vs-olap.md)。

用相同公式在 MySQL 与 ClickHouse 中各生成 500 万行订单，先核对三条查询的结果逐字节相同，再比较全表聚合、范围聚合与点查的耗时（各预热 3 次、采样 7 次），记录 ClickHouse 的读取行数、字节数与各列压缩后大小。

**轻量实验**：保留核心脚本、一条验证入口、原始输出和简要验证记录。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 2 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
