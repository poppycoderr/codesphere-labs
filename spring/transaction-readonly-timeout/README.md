# readOnly 与 timeout 实际做了什么

对应文章：[transaction-readonly-timeout.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/transaction-readonly-timeout.md)。

Spring Boot 4.1.2 + Spring Data JPA（JpaTransactionManager）。MySQL 8.4.11 由 Testcontainers 启动（固定 digest），另有一个 H2 上下文做对照。`readOnly` 测 JDBC 写入与 JPA 脏检查，`timeout` 测「最后一条语句之后的耗时」「耗时之后再执行语句」「单条慢语句」三种情况。

```bash
make verify     # 需要 JDK 21+、Maven、Docker
make evidence   # 重新采集 evidence/
make clean
```
