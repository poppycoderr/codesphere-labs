# Spring 循环依赖

对应文章：[circular-dependencies.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/circular-dependencies.md)。

Maven 项目，Spring Boot 4.1.1（Spring Framework 7.0.9）。`labs.cycles.Cycles` 里每组互相引用的两个类放在一个嵌套配置里，测试按需加载：

| 配置 | 内容 |
|---|---|
| `ConstructorCycle` | 两个类通过构造器互相依赖 |
| `FieldCycle` | 两个类通过 `@Autowired` 字段互相依赖 |
| `TransactionalCycle` | 字段循环，一侧有 `@Transactional` 方法 |
| `AsyncCycle` | 字段循环，一侧有 `@Async` 方法；构造器里计数 |
| `PrototypeCycle` | 两个 prototype 作用域的类字段循环 |
| `LazyConstructorCycle` | 构造器循环，一侧参数加 `@Lazy` |
| `Extracted` | 拆出 `Checkout` 依赖订单与库存，两者互不依赖 |

纯 Spring Framework 容器用 `AnnotationConfigApplicationContext`，Spring Boot 用 `SpringApplication`（不启动 Web）。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven
make evidence
make clean
```
