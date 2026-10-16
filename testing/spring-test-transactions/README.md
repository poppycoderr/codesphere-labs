# Spring 测试事务的失真

对应文章：[spring-test-transactions.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/spring-test-transactions.md)。

一个 Spring Boot 4.1.1 小应用（H2 内存数据库、Spring Data JPA）：会员注册（邮箱唯一，id 由应用指定，插入推迟到 flush）、订单与懒加载的订单行、注册提交后由 `@TransactionalEventListener` 发送通知、在 `REQUIRES_NEW` 事务里写审计、在另一个线程里读订单。`A_TransactionalTests` 给每个测试加 `@Transactional`（结束时回滚），`B_CommittedTests` 用完全相同的断言但不加测试事务；`C_MockedBeanTests` 用 `@MockitoBean` 替换一个 Bean；`Z_ContextCountTest` 最后运行，记录整个过程中创建的应用上下文个数。测试类按字母顺序运行。`scripts/verify.sh` 在容器里运行 `mvn test`（失败不中断），由 `src/Report.java` 汇总 surefire 报告。

## 快速运行

```bash
make verify     # 需要 Docker；约 2 分钟（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
