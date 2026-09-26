# Spring 事务传播

对应文章：[transaction-propagation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/transaction-propagation.md)。

Spring Boot 4.1.1 + `spring-boot-starter-jdbc`（自动配置 `JdbcTransactionManager`），MySQL 8.4.11 由 Testcontainers 启动。连接池只有 2 个连接、获取连接最多等 1 秒；每个连接的 `innodb_lock_wait_timeout` 设为 2 秒。

| 测试 | 场景 |
|---|---|
| `PropagationTest` | REQUIRED 吞异常、`noRollbackFor`、NESTED 与 REQUIRED 的批量导入对照、REQUIRES_NEW 独立提交、连接池耗尽、同行锁等待、受检异常默认提交、AFTER_COMMIT |
| `RollbackOnAllExceptionsTest` | `@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)` 下受检异常回滚 |

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven 与 Docker；约 1 分钟
make evidence
make clean
```
