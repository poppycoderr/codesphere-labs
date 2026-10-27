# Spring Data 领域事件与事务阶段

对应文章：[data-domain-events.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/data-domain-events.md)。

Spring Boot 4.1.2 + Spring Data JPA + H2。测试分两个上下文：默认连接池配置，以及 `spring.datasource.hikari.auto-commit=false`。覆盖 `AbstractAggregateRoot` 的发布与清空、只有 `@DomainEvents` 的聚合、依赖脏检查时不发布、在持久化对象构造器里登记事件、`@TransactionalEventListener(AFTER_COMMIT)` 里写数据库。

```bash
make verify     # 需要 JDK 21+、Maven
make evidence   # 重新采集 evidence/
make clean
```
