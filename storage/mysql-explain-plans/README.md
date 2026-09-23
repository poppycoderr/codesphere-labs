# Explain 执行计划与索引失效：10 万行订单表的计划、实际行数与 ICP 计数

对应文章：[mysql-explain.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-explain.md)。

用同一张确定性生成的 10 万行订单表，分五个索引阶段采集 24 条查询的 `EXPLAIN`、`EXPLAIN FORMAT=JSON`、`EXPLAIN ANALYZE`、Handler 计数与 InnoDB 的 ICP 计数器，覆盖 Explain 篇全部示例和调优篇第三、四节（联合索引列顺序、索引用不上的几类原因）。

**轻量实验**：保留核心脚本、一条验证入口、原始输出和简要验证记录。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
