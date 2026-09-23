# MVCC 与隔离级别：两会话时序、幻读三种读法、半一致性读与长事务

对应文章：[mysql-mvcc-and-isolation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-mvcc-and-isolation.md)。

两个会话按固定延时交错执行，验证 RR 与 RC 的快照建立时机、快照读与当前读混用、幻读的三种读法、无索引 UPDATE 的半一致性读，以及长事务让 History list length 持续上涨。

**轻量实验**：保留核心脚本、一条验证入口、原始输出和简要验证记录。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 1.5 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
