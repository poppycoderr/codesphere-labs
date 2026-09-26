# 验证记录：Spring 事务传播

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **REQUIRED 下内层失败会波及外层**：内层 `@Transactional` 方法抛出运行时异常，外层 `catch` 后正常返回，提交时抛出 `UnexpectedRollbackException`，订单没有落库。
2. **内层声明 `noRollbackFor`**：同样的调用，外层正常提交，订单落库。
3. **NESTED 回滚到保存点**：批量导入 3 行，第 2 行写入后校验失败；NESTED 下提交后留下第 1、3 行。同样的批次改用 REQUIRED，整批回滚并抛出 `UnexpectedRollbackException`，一行都不留。
4. **REQUIRES_NEW 独立提交**：外层订单回滚，内层写入的审计记录保留。
5. **REQUIRES_NEW 需要第二个连接**：连接池 2 个连接，两个外层事务各占一个后都调用 REQUIRES_NEW，二者都在等满 1 秒连接超时后失败，异常为 `CannotCreateTransactionException`，根因 `SQLTransientConnectionException`。
6. **REQUIRES_NEW 与外层争同一行锁**：外层 `SELECT ... FOR UPDATE` 锁住账户行，内层独立事务更新同一行，等满 `innodb_lock_wait_timeout`（2 秒）后抛出 `CannotAcquireLockException`（`Lock wait timeout exceeded`）。InnoDB 看到的是两个事务之间的普通锁等待，不会当作死锁立即回滚一方。
7. **回滚规则**：默认规则下抛出受检异常，事务提交；`@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)` 时同样的调用回滚。
8. **AFTER_COMMIT**：`@TransactionalEventListener(phase = AFTER_COMMIT)` 只收到提交的订单，回滚的订单没有收到事件。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。JDK 21.0.5，Spring Boot 4.1.1，Spring Framework 7.0.9，HikariCP（Boot 管理的版本），MySQL 8.4.11（镜像固定 digest）。

## 三、执行步骤

`mvn test` 启动一个 MySQL 容器，两个测试类各启动一次 Spring 容器；每个测试前清空数据。测试把观察到的结果写入 `target/facts.tsv`，`scripts/verify.sh` 对其逐条断言。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证了 JDBC 事务管理器（`JdbcTransactionManager`）。NESTED 在 JPA 事务管理器下的行为没有验证，文章沿用官方文档「只适用于 JDBC 资源事务」的说法。
- 连接池耗尽是用 2 个连接、2 个并发请求构造的确定场景；真实系统里是否发生取决于连接池大小与并发数。
- 锁等待超时设为 2 秒只为缩短实验时间，MySQL 默认值是 50 秒。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过；与文章结论一致 | 补「配套实验」，把「官方文档说明」类表述中已实测的部分改为实测 |
