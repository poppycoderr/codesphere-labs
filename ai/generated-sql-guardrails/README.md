# 执行生成的 SQL 之前的检查

对应文章：[generated-sql-guardrails.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/generated-sql-guardrails.md)。

实验不调用任何模型。它用一组预置的语句代表「模型可能写出来的 SQL」，在 MySQL 8.4.11 上逐层检查每道限制能挡住什么、挡不住什么。

- `schema/setup.sql`：5 个用户（含 `phone` 列）、5 笔订单、8 行明细、10 万行事件；一个 DEFINER 视图与一个 INVOKER 视图；读写账号 `app_rw` 与只授予部分表和视图 `SELECT` 的 `report_ro`。密码是演示值。
- `scripts/verify.sh`：依次验证账号权限、只读事务、`PREPARE` 多语句、会话超时与语句提示、`sql_select_limit` 与外层 `LIMIT`、`EXPLAIN FORMAT=JSON` 的成本、与手算答案对比。
- `src/JdbcGuard.java`：Connector/J 9.7.0（jar 校验 sha256）默认连接参数下的多语句、`setReadOnly`、`setQueryTimeout`、`setMaxRows`。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 1 分钟
make evidence
make clean
```
