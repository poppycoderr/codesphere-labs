# 表设计三个细节：逻辑删除的唯一约束、IP 地址存储、热点行更新

对应文章：[mysql-schema-design-details.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-schema-design-details.md)。

验证唯一索引包含可空列时逻辑删除约束失效，以及 `deleted_id` 与函数索引两种写法；100 万个 IPv4 地址分别以字符串和 `VARBINARY(16)` 存储时的网段查询结果与索引大小；1、16、64 个线程更新同一行、分散更新、拆成 4 或 16 个桶时的吞吐与锁等待次数。

**轻量实验**：保留核心脚本、一条验证入口、原始输出和简要验证记录。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、网络可访问 Maven Central（首次下载 Connector/J）；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
