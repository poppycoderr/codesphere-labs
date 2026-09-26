# 验证记录：Spring Boot 的默认异步执行器

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **默认执行器的参数**：`applicationTaskExecutor` 是 `ThreadPoolTaskExecutor`，`corePoolSize=8`，`maxPoolSize` 与队列容量都是 `Integer.MAX_VALUE`（2147483647）。
2. **队列无界时最大线程数不起作用**：提交 50 个卡住的 `@Async` 任务，线程数停在 8，另外 42 个在队列里排队，没有任务被拒绝。
3. **开启虚拟线程后执行器换了类型**：`spring.threads.virtual.enabled=true` 时，`applicationTaskExecutor` 是 `SimpleAsyncTaskExecutor`，`@Async` 方法运行在虚拟线程上，50 个任务同时运行。它默认没有并发上限，原来由线程池承担的限流要另外补上。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。JDK 21.0.5，Spring Boot 4.1.1。

## 三、执行步骤

`mvn test`：两个测试类各启动一个 Spring 容器；任务都卡在同一个 `CountDownLatch` 上，提交后等 300ms 再读取线程数、队列长度和同时运行的任务数。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证了不自定义 `Executor` 时的自动配置。项目里一旦声明了自己的 `Executor` bean，自动配置会让位，结论不再适用。
- 300ms 足够让任务启动；如果机器很慢，同时运行的数量可能还没到位，断言会失败而不是给出错误结论。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过；与文章一致 | 补「配套实验」 |
