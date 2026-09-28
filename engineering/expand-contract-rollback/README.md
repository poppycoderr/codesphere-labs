# 发布的可回退性：扩展—迁移—收缩

对应文章：[expand-contract-rollback.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/expand-contract-rollback.md)。

单文件程序 `src/Rollback.java`，连接共享的 MySQL 8.4.11。把报名表的 `phone` 列改名为 `mobile`；版本用代码路径表示，每个版本只在自己知道的列上读写，每一步让在线的每个版本各处理 20 次「报名并读回手机号」：

1. 原地改名：`RENAME COLUMN` 一次完成；滚动发布期间 v1 与 v2 并存；之后回退到 v1；
2. 扩展—迁移—收缩：加列 → v1 与双写的 v1.5 并存 → 回填 → 读新列的 v2 与 v1.5 并存 → 回退到 v1.5、再到 v1 → 重新发布 v2 并再次回填 → 删除旧列 → 尝试回退到 v1；
3. 版本 × 表结构矩阵：四个版本分别在「只有 phone」「两列都有」「只有 mobile」三种表上各跑 20 次；
4. 事件：只认识 `id`、`attendee`、`phone` 的旧消费者，读取新增了字段、以及把 `phone` 改名为 `mobile` 的新事件，分别用严格解析（未知字段报错）与宽容解析（忽略未知字段）。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）；约 30 秒（不含拉取镜像）
make evidence
make clean
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
