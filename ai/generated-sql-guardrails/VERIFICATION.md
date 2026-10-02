# 验证记录：执行生成的 SQL 之前的检查

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

MySQL 8.4.11，Connector/J 9.7.0，JDK 25.0.4。

1. **最小权限账号**（只授予部分表和视图的 `SELECT`）：`DELETE`、读未授权的表、读 `mysql.user`、`UPDATE` 视图为 ERROR 1142；`INTO OUTFILE` 为 ERROR 1227；`SELECT … FOR UPDATE` 为 ERROR 1142（`SELECT with locking clause command denied`）。DEFINER 视图可读（返回 5）；INVOKER 视图为 ERROR 1356。`SELECT SLEEP(2)` 正常执行，用时不少于 2 秒。
2. **只读事务**：读写账号在 `START TRANSACTION READ ONLY` 里 `DELETE` 为 ERROR 1792，数据不变。
3. **单语句**：`PREPARE` 两条语句为 ERROR 1064，数据不变。
4. **超时**：会话 `MAX_EXECUTION_TIME=500` 时，`SLEEP(2)` 在 1 秒内返回 1（被打断），10 万行自连接为 ERROR 3024；同一会话里语句带 `/*+ MAX_EXECUTION_TIME(20000) */` 时，`SLEEP(2)` 返回 0，用时不少于 2 秒。
5. **输出上限**：`sql_select_limit=3` 时没写 `LIMIT` 的查询返回 3 行，语句自带 `LIMIT 10` 时返回 10 行；外层包 `LIMIT 4` 时返回 4 行。
6. **成本估算**：按状态汇总 5 行订单 `query_cost=0.75`；10 万行事件表按低基数列自连接 `query_cost` 约 10 亿（本次 1003413900.76）。查询账号执行 `EXPLAIN DELETE` 为 ERROR 1142。
7. **黄金数据**：订单连明细后求订单金额合计得 880.00，手算答案 480.00；直接在订单表求和得 480.00。
8. **JDBC**：默认连接参数下执行两条语句抛 `SQLSyntaxErrorException`；`setReadOnly(true)` 后 `DELETE` 抛 `SQLException（Connection is read-only…）`；语句带 `MAX_EXECUTION_TIME(20000)` 提示、`setQueryTimeout(1)` 时 3 秒内抛 `MySQLTimeoutException`；`setMaxRows(100)` 返回 100 行。实验结束后 `orders` 仍为 5 行。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/mysql-version.txt`](evidence/mysql-version.txt)。

## 三、执行步骤

见 `scripts/verify.sh`。超时一节用 `SLEEP` 的返回值（0 为睡满、1 为被打断）判断是否被终止，避免依赖机器速度。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- `query_cost` 依赖统计信息的采样，两次运行分别为 953758297.68 与 1003413900.76；断言只检查小查询小于 10、自连接大于 1 亿。成本是优化器的估算，不是耗时。
- 没有调用模型，预置语句不代表任何模型的实际输出分布，也不评价生成质量。
- 只验证了 MySQL 8.4.11 与 Connector/J 9.7.0；其他数据库的权限模型、只读事务与超时机制不同。
- 没有覆盖：存储过程与函数的权限、`information_schema` 可见范围的细节、通过合法查询推断敏感数据（聚合推断）、连接数与并发上限。
- JDBC 的 `setReadOnly` 行为与连接参数有关，实验使用默认参数。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

脚本退出时删除 MySQL 容器与网络。

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
