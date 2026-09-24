# 本地事件的投递

对应文章：[events-observer-and-event-bus.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/events-observer-and-event-bus.md)。

Maven 项目（Spring Framework 7.0.9、JUnit 5.13.4）。报名服务保存报名后发布事件，订阅者有两个带 `@Order` 的同步监听器、一个 `@Async` 监听器（线程 1、队列 2）和一个 `@TransactionalEventListener(AFTER_COMMIT)`。

为了不依赖数据库，`LocalTransactionManager` 只驱动 Spring 的事务同步回调，`RegistrationStore` 在事务提交后才让写入可见、回滚时丢弃。7 个测试覆盖同步、异步、拒绝、无事务、提交后失败，以及带 `@Async` 的 Bean 是 CGLIB 代理这一细节。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven；首次下载依赖
make evidence   # 重新采集 evidence/
make clean      # 清理本实验的构建产物
```
