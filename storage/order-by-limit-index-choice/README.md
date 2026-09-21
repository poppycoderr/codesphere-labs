# ORDER BY id LIMIT：优化器为什么放着好索引不用

对应文章：[SQL 调优实战：优化器为什么放着好索引不用](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/database-design-and-tuning.md) 第二节。

一张 300 万行的任务事件表，待处理数据集中在最近写入的 5%。定时任务按 `ORDER BY id LIMIT 100` 取待处理事件，优化器选择沿主键扫描，实际读了 285 万行。实验在相同条件下比较原查询与四种修复写法，并用两条对照查询说明优化器的假设在什么情况下成立。

这也是 codesphere 数据库类实验的证据格式样板：固定镜像 digest、资源限制、确定性造数、预热、多次采样、完整执行计划、Handler 与缓冲池计数、结果一致性校验。

## 快速运行

```bash
make verify     # 需要 Docker 与 Python 3；首次约 1 分钟（不含拉取镜像），占用约 1 GB 磁盘
make clean      # 删除容器与数据卷
```

容器限制为 2 CPU、2 GB 内存，不暴露宿主机端口，compose 项目名为 `csl-order-by-limit`。

## 目录

| 路径 | 内容 |
|---|---|
| `compose.yaml`、`config/mysql.cnf` | 固定 digest 的 MySQL 8.4.11 与配置 |
| `schema/` | 建表、确定性造数、统计信息 |
| `queries/` | `00` 原查询；`01`—`04` 四种修复；`10`、`11` 对照 |
| `scripts/` | `setup`、`seed`、`collect`、`run`、`summarize`、`verify`、`cleanup` |
| `evidence/` | 每条查询的执行计划、计数与汇总 |
| `VERIFICATION.md` | 验证记录与结论边界 |
