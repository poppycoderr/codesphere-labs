# Spring AOP 代理失效：哪些调用会被拦截

对应文章：[Spring AOP 为什么失效](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/aop-proxy-pitfalls.md)。

用真实的 Spring 容器（`AnnotationConfigApplicationContext`，Spring Framework 7.0.9）逐一验证：自调用、`AopContext`、`final` 方法、包级可见方法、`private` 方法、切换线程、受检与运行时异常、JDK 接口代理，以及 Spring 6.2 起的 `rollbackOn = ALL_EXCEPTIONS`。

事务管理器是一个只记录「开启、提交、回滚」的实现（`Recorder.TxManager`），不连接数据库。这样每个断言只关心**事务切面有没有生效**，不受数据库行为干扰。

## 快速运行

```bash
make verify     # 需要 JDK 21+ 与 Maven 3.9+；首次运行会下载依赖，之后约 10 秒
```

不占用端口，不启动容器。

## 目录

| 路径 | 内容 |
|---|---|
| `src/main/java/labs/aop/OrderService.java` | 九种调用方式对应的方法 |
| `src/main/java/labs/aop/Configs.java` | 类代理、默认配置、所有异常都回滚三种容器配置 |
| `src/main/java/labs/aop/Recorder.java` | 记录事务边界与方法执行时的事务状态 |
| `src/main/java/labs/aop/Demo.java` | 按文章顺序打印的演示程序，输出即应用日志 |
| `src/test/java/labs/aop/ProxyPitfallsTest.java` | 12 个断言，对应文章第三节表格的每一行 |
| `evidence/` | 测试结果、应用日志、依赖版本、环境 |
