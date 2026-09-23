# 单表规模：B+ 树层高、扇出、点查的冷热差异与大表 DDL 耗时

对应文章：[mysql-table-size-and-sharding.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-table-size-and-sharding.md)。

直接读取四张不同行宽、行数的表在 `.ibd` 中主键根页的 `PAGE_LEVEL` 与 `PAGE_N_RECS`，结合叶子页数算出中间页扇出；对比热缓存下 2 层与 3 层的点查耗时、冷热两轮的差异，并测量 2,000 万行窄表上的 INSTANT、INPLACE、COPY 三类 DDL。

**轻量实验**：保留核心脚本、一条验证入口、原始输出和简要验证记录。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 3—4 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
