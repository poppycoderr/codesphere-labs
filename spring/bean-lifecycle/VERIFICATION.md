# 验证记录：Spring Bean 生命周期

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **单例启动时的回调顺序**：`BeanFactoryPostProcessor` 修改 `priceCache` 的定义 → `Clock` 构造 → `PriceCache` 构造（注入 `Clock`）→ 属性填充 `timeoutMs=500` → `BeanNameAware` → `BeanFactoryAware` → `ApplicationContextAware` → `BeanPostProcessor.before` → `@PostConstruct` → `afterPropertiesSet` → `@Bean(initMethod)` → `BeanPostProcessor.after`（此时拿到的已经是代理）→ `SmartInitializingSingleton.afterSingletonsInstantiated` → `SmartLifecycle.start` → `ContextRefreshedEvent`。
2. **关闭时的顺序**：`ContextClosedEvent` → `SmartLifecycle.stop` → `@PreDestroy` → `DisposableBean.destroy` → `@Bean(destroyMethod)`。
3. **容器交出的是代理**（类代理）：`getBean` 返回 `PriceCache$$SpringCGLIB$$…`，与构造出的实例不是同一个对象。`@PostConstruct` 里通过 `this` 调用 `@Transactional` 方法，事务不活跃；启动后经容器取得的对象调用，事务活跃。`BeanFactoryPostProcessor` 设置的 `timeoutMs=500` 生效。
4. **JDK 动态代理**：`PriceCache` 实现了接口，`@EnableTransactionManagement` 默认创建 JDK 动态代理；这时按实现类型 `getBean(PriceCache.class)` 抛出 `NoSuchBeanDefinitionException`。
5. **prototype 不销毁**：取 2 次 prototype Bean 后关闭容器，`@PostConstruct` 执行 2 次，`@PreDestroy` 0 次。
6. **提前创建的 Bean 错过代理**：一个实现 `PriorityOrdered` 的后处理器通过构造器依赖 `AuditService`，`AuditService` 在创建代理的后处理器注册之前就被创建，不是代理，调用它的 `@Transactional` 方法时事务不活跃。容器打印警告：`Bean 'auditService' … is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying)`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)、[`evidence/dependencies.txt`](evidence/dependencies.txt)。Spring Framework 7.0.9，JDK 21。

## 三、执行步骤

`mvn test` 运行 `LifecycleTest` 的 5 个用例，每个用例新建容器；回调顺序由 `Journal` 记录，事实写入 `target/facts.tsv`；警告日志从 Maven 输出中截取。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/facts.tsv` | 每个用例记录的事实 |
| `evidence/early-bean-warning.txt` | 容器对提前创建的 Bean 打印的警告 |
| `evidence/test-results.txt` | 用例名与结果 |
| `evidence/maven-test.log` | Maven 输出 |
| `evidence/dependencies.txt` | 依赖版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 这是纯 Spring Framework 容器。Spring Boot 默认 `spring.aop.proxy-target-class=true`，第 4 条的 JDK 代理问题在 Boot 应用里默认不会出现。
- 「普通后处理器在 after 阶段拿到代理」取决于后处理器的注册顺序：负责代理的后处理器实现了 `Ordered`，排在没有实现排序接口的后处理器之前。
- 事务管理器只驱动事务同步回调，不连接数据库。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-25 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
