# 运行中改配置：收到与生效

对应文章：[config-received-not-applied.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/config-received-not-applied.md)。

`src/ConfigLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，用四个真实的组件各验证一种「新值已经到了，行为没有变」的情况：

- Spring Framework 7.0.9：往 `Environment` 里加一个可变的属性源代替配置中心客户端，启动后修改其中的值，看 `Environment.getProperty`、已注入的 `@Value` 字段、启动时用这个值算出来的对象、之后新建的 Bean 各自读到什么。
- JDK 的 `ThreadPoolExecutor`：运行中调用 `setMaximumPoolSize` 与 `setCorePoolSize`，看实际线程数。
- Logback 1.5.38：运行中把 root 的级别调低，看继承级别的 logger 与显式配置过级别的 logger。
- HikariCP 7.0.2：连接池启动后修改最大连接数、JDBC 地址、口令。连接池指向一个连不上的本地地址，只用来触发启动，不需要数据库。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 20 秒
make evidence
make clean
```
