# Spring Bean 生命周期

对应文章：[bean-lifecycle.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/bean-lifecycle.md)。

Maven 项目（Spring Framework 7.0.9、jakarta.annotation-api 3.0.0、JUnit 5.13.4）。`PriceCache` 实现了几乎所有生命周期接口，每个回调都记入 `Journal`；它的 `refresh()` 带 `@Transactional`，因此最终会被代理。事务管理器 `LocalTransactionManager` 不连接任何资源，只用来判断调用时是否处在事务中。

五个用例：

- 单例启动与关闭时的回调顺序，`BeanFactoryPostProcessor` 修改定义的时机；
- 容器交出的对象与构造出的实例是否相同，`@PostConstruct` 里调用 `@Transactional` 方法时事务是否生效（类代理配置）；
- `@EnableTransactionManagement` 默认的 JDK 动态代理下，能否按实现类型 `getBean(PriceCache.class)`；
- prototype 作用域的初始化与销毁回调；
- 一个实现 `PriorityOrdered` 的后处理器通过构造器依赖普通 Bean，那个 Bean 能否被代理。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven
make evidence
make clean
```
