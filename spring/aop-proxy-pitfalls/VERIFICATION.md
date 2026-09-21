# 验证记录：Spring AOP 代理失效的九种调用方式

## 一、待验证结论

| # | 文章中的结论 | 测试 |
|---|---|---|
| 1 | `proxyTargetClass = true` 时，Bean 的运行时类型是 `$$SpringCGLIB$$` 子类 | `cglib` |
| 2 | 外部调用 `@Transactional` 方法会开启并提交事务 | `external` |
| 3 | 目标对象内部 `this.persist()` 自调用没有事务 | `selfInvocation` |
| 4 | `exposeProxy = true` 后，`AopContext.currentProxy()` 调用经过代理 | `currentProxy` |
| 5 | `final` 方法没有事务，并且在代理对象上执行，读到 `repo = null` | `finalMethod` |
| 6 | Spring 6.0 起，类代理可以拦截包级可见方法 | `packagePrivate` |
| 7 | `private` 方法任何时候都不会被拦截 | `privateMethod` |
| 8 | 事务方法里切换线程，新线程中没有事务 | `switchThread` |
| 9 | 默认回滚规则：受检异常提交，运行时异常回滚 | `checkedCommits`、`uncheckedRollsBack` |
| 10 | 默认配置下类实现了接口时使用 JDK 代理，按实现类取 Bean 抛 `NoSuchBeanDefinitionException` | `jdkProxyByDefault` |
| 11 | Spring 6.2 起 `@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)` 让受检异常也回滚 | `rollbackOnAllExceptions` |

## 二、适用版本与环境

- Spring Framework 7.0.9、AspectJ Weaver 1.9.22、JUnit Jupiter 5.13.4，完整依赖见 [`evidence/dependencies.txt`](evidence/dependencies.txt)。
- JDK 21.0.5、Maven 3.9.9、macOS arm64，见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、场景与数据集

一个 `OrderService` Bean 和一个被注入的 `Repo` Bean。事务管理器继承 `AbstractPlatformTransactionManager`，只记录事务边界，不访问数据库。业务方法用 `TransactionSynchronizationManager.isActualTransactionActive()` 记录自己是否处在事务中。

## 四、执行步骤

`scripts/verify.sh` 依次执行：

1. `mvn test`，运行 12 个测试；
2. 从 surefire 的 XML 报告中提取用例名与结果，写入 `test-results.txt`；
3. `mvn exec:java` 运行 `Demo`，按文章顺序打印每种调用的事务边界，写入 `application.log`；
4. `mvn dependency:list` 记录实际解析的依赖版本；
5. 对以上输出逐项断言。

## 五、原始证据索引

| 证据 | 内容 |
|---|---|
| [`evidence/test-results.txt`](evidence/test-results.txt) | 12 个用例的结果 |
| [`evidence/maven-test.log`](evidence/maven-test.log) | `mvn test` 的完整输出，包括测试过程中打印的事务边界 |
| [`evidence/application.log`](evidence/application.log) | 演示程序输出，与文章第三节的表格一一对应 |
| [`evidence/dependencies.txt`](evidence/dependencies.txt) | 解析后的运行时依赖 |

## 六、实际结果

- `test-results.txt`：`tests=12 failures=0`。
- `application.log` 中与文章表格对应的关键行：自调用时 `persist()：没有事务`；`final` 方法 `repo = null`；新线程 `asyncThread()：没有事务`；受检异常 `[tx] commit`，运行时异常 `[tx] rollback`；默认配置下按实现类取 Bean 抛 `NoSuchBeanDefinitionException`；`rollbackOn = ALL_EXCEPTIONS` 时受检异常 `[tx] rollback`。
- 容器启动时 Spring 输出一条警告：`Public final method ... cannot get proxied via CGLIB`。这条警告只在启动时出现一次，运行时调用 `final` 方法不会再有任何提示。

## 七、结果解释

- 测试验证的是「事务切面是否被调用」，这正是文章讨论的问题。事务管理器是否真正提交或回滚了数据库，属于 `spring-tx` 与 JDBC 驱动的职责，不在本实验范围内。
- `final` 方法读到 `repo = null`，说明调用直接落在 CGLIB 生成的代理对象上，而代理对象的字段没有被注入。

## 八、误差、限制与不能推出的结论

- 没有使用 Spring Boot。Spring Boot 默认使用类代理（`spring.aop.proxy-target-class=true`），文章中的这一说法目前只以官方文档为依据，已登记为项目级验证项，下一阶段用 Spring Boot 4 + Testcontainers 统一验证。
- 没有连接真实数据库，不能推出「数据确实回滚」；真实数据库上的回滚行为同样留到项目级验证。
- 只验证了 `@Transactional`。`@Async`、`@Cacheable` 的自调用问题原理相同，但没有单独测试。

## 九、复现与清理命令

```bash
make verify     # 输出到 target/run
make evidence   # 重新生成 evidence/
make clean      # mvn clean
```

## 十、验证历史

| 日期 | 环境 | 结果 | 文章是否需要更新 |
|---|---|---|---|
| 2026-09-21 | Spring 7.0.9，JDK 21.0.5 | 文章发布时用单文件演示程序验证 | — |
| 2026-09-22 | 同上，改为 Maven 工程与 12 个 JUnit 断言 | 全部通过，结果与文章一致 | 否，文末补充配套实验链接 |
