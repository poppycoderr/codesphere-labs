# 持久化与重建

对应文章：[persistence-and-rehydration.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/persistence-and-rehydration.md)。

MySQL 8.4.11 容器 + JDK 21 容器运行单文件程序 `src/Persistence.java`。场次聚合（容量、已确认名单、候补队列、版本号）是纯 Java 对象，仓储用 JDBC 显式映射到 `session` 与 `session_attendee` 两张表（`schema/01-schema.sql`）。五组对照：

1. 保存—重建往返：新连接重建后的业务状态与保存前一致，重建不产生待发布事件；
2. 重建不是创建：上线「新场次容量至少 10」后，历史场次（容量 5）仍能重建；按创建路径重放则被拒绝或重复登记事件；
3. 版本号冲突：两个请求从同一版本加载，后提交者 `UPDATE ... WHERE version=?` 影响 0 行，整笔回滚；
4. 子表的保存方式：已有 1000 个参会人时新增 1 人，按差异保存与整体替换各写多少行；
5. 票种（免费、付费、团体）继承的三种映射：单表、每个具体类一张表、父表 + 子表，各 9 万张票，比较结果、执行计划、占用空间与约束能力。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
