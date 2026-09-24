# CQRS：从查询分离到读模型

对应文章：[cqrs-without-overengineering.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/cqrs-without-overengineering.md)。

运营看板要看「活动 1 各场次的确认数、候补数、已收金额」。200 个场次、5 万条报名，MySQL 8.4.11 容器 + JDK 21 容器运行 `src/Cqrs.java`：

1. **经仓储加载聚合**：逐个加载场次聚合（根 + 全部报名）再在内存里汇总；
2. **GROUP BY**：同一个库、同一组表，只是查询不走聚合，直接写 SQL；
3. **同步读模型** `session_stats`：报名命令在同一事务里多更新一行统计；
4. **异步读模型** `session_stats_async`：投影器按 `domain_event` 表的顺序批量更新，checkpoint 与投影结果同一事务提交。演示暂停、追赶与清空重放。

三种同步读法的结果按 SHA-256 摘要比较；写路径分别在有无同步投影时顺序执行 1000 次报名命令，各 3 轮。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
