# 验证记录：readOnly 与 timeout 实际做了什么

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| MySQL：readOnly 事务里 JdbcTemplate 插入 | 驱动报 Connection is read-only，未写入 |
| H2：同样的代码 | 插入成功 |
| readOnly 事务里修改已加载实体 / 再显式 save | 都没有报错，也都没有写回 |
| timeout 1 秒，插入后 Java 里耗时 1.5 秒 | 正常提交 |
| timeout 1 秒，先耗时 1.5 秒再插入 | TransactionTimedOutException，未写入 |
| timeout 1 秒，一条 SELECT SLEEP(3) | 约 1 秒后 QueryTimeoutException |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。

## 三、执行步骤

`mvn test` 运行 `src/test` 下的 JUnit 测试，测试把观察到的事实写入 `target/facts.tsv`；`scripts/verify.sh` 逐行断言。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- readOnly 在 JDBC 层的效果取决于驱动：Connector/J 9.7.0 在客户端拦截写语句；其他驱动可能只是提示，或把只读传到服务端。
- 耗时用 Thread.sleep 模拟，判断用「是否超过 1 秒」「是否在 1—2 秒之间」这样的区间，不是精确计时。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
