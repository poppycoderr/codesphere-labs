# 验证记录：Spring Boot 下的代理

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **Boot 默认用类代理**：`OrderService` 实现了接口，Spring Boot 默认配置下按实现类 `getBean(OrderService.class)` 成功，代理是 CGLIB。纯 Spring Framework 默认配置下同样的代码抛 `NoSuchBeanDefinitionException`，见 [spring/aop-proxy-pitfalls](../aop-proxy-pitfalls/)。
2. **`@Cacheable` 自调用绕过缓存**：类内部连续调用两次，方法体执行 2 次；从外部调用两次，只执行 1 次。
3. **`@Async` 自调用同步执行**：从外部调用运行在 `task-N` 线程上；类内部调用运行在调用方线程（`main`）上。
4. **缓存与事务切面的顺序**（同一个方法同时有 `@Transactional` 与 `@Cacheable`）：

| 顺序 | 缓存命中 10 次开启的物理事务 | 事务回滚后第二次调用 |
|---|---:|---|
| 缓存在外层（`@EnableCaching(order = 1)`） | 0 | 重新执行，再次回滚 |
| 事务在外层（`@EnableTransactionManagement(order = 1)`） | 10 | 直接返回缓存里的 `new-7`，数据库里没有这一行 |
| 默认（两者都不指定） | 0 | 重新执行，再次回滚 |

事务在外层时，缓存切面在事务提交之前就写入了结果；提交时发现事务已被标记为只能回滚，数据回滚了，缓存里的值却留了下来。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。JDK 21.0.5，Spring Boot 4.1.1，H2（Boot 管理的版本），缓存为 Boot 默认的 `ConcurrentMapCacheManager`。

## 三、执行步骤

`mvn test`：4 个测试类，其中 3 个用不同的 `labs.order` 各启动一个 Spring 容器。事务管理器继承 `JdbcTransactionManager`，在 `doBegin` 里计数；「回滚」场景中，方法插入一行后调用一个总是失败的 `@Transactional` 审计方法并吞掉异常，共享事务被标记为只能回滚。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 默认顺序下的表现取决于两个切面的注册顺序，本实验里与「缓存在外层」一致，但这不是 Spring 承诺的行为，不能依赖；需要确定的顺序时要显式指定 `order`。
- 缓存用的是进程内的 `ConcurrentMapCacheManager`。换成 Redis 等外部缓存，写入时机相同，但回滚后残留的值会被所有实例读到。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过 | 需要：文章把「缓存切面在事务外层」写成了会缓存未提交数据的一方，实测相反 |
