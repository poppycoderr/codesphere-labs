# 验证记录：运行中改配置：收到与生效

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 属性源里的 `limit.qps` 从 100 改成 500，`Environment.getProperty` | 500 |
| 启动时注入的 `@Value` 字段 | 100 |
| 启动时用这个值算出来的对象 | 仍按 100 计算 |
| 此后新创建的 Bean | 500 |
| 线程池核心 4、最大 4、无界队列，20 个阻塞任务 | 实际线程 4，排队 16 |
| 把最大线程数改成 16 | `getMaximumPoolSize` 返回 16，实际线程仍是 4，排队 16 |
| 核心 4、最大 4 的池先把核心线程数改成 16 | `IllegalArgumentException` |
| 最大线程数已是 16，再把核心线程数改成 16 | 实际线程 16，排队 4 |
| 任务还在执行时把核心线程数改回 4 | 实际线程仍是 16 |
| 任务结束、空闲超过保活时间后 | 实际线程 4 |
| 核心 4、有界队列容量 10、5 个在排队，最大线程数从 4 改成 8 | 实际线程仍是 4 |
| 再提交 7 个任务 | 队列满（10），实际线程 6 |
| 运行中把 root 从 INFO 调成 DEBUG | 继承级别的 logger 变成 DEBUG；显式配置为 WARN 的 logger 仍是 WARN |
| HikariCP 启动后 `setMaximumPoolSize(30)` | 成功 |
| 启动后 `setJdbcUrl` | `IllegalStateException`：配置在启动后封住 |
| 启动后 `setPassword` | 成功 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- Spring 部分只用了 `spring-context`，可变属性源是实验自己加的。Spring Cloud 的 `@RefreshScope`、Spring Boot 的 `@ConfigurationProperties` 重新绑定、各配置中心客户端的刷新机制都没有覆盖，它们各自解决其中一部分，以实际使用的组件为准。
- HikariCP 的 `setPassword` 只验证了调用成功；新口令在新建连接时才会被使用，实验里连接池连不上数据库，没有验证这一步。
- 线程池的结果来自 JDK 25.0.4.1。核心线程数大于最大线程数时抛异常是 JDK 9 之后的行为。
- 没有覆盖多个配置项需要一起生效、以及多实例之间生效时间不一致的情况。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-10 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
