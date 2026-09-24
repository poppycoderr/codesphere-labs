# 验证记录：本地事件的投递

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 同步监听器按 `@Order` 在发布者线程执行，异步监听器在 `async-1` 线程，提交后监听器在提交后、仍在发布者线程执行。
2. 同步监听器抛出异常：发布者收到异常，报名回滚（0 条），提交后监听器不执行。
3. `@Async` 监听器抛出异常：发布者在监听器完成前返回，报名已提交，异常只进入 `AsyncUncaughtExceptionHandler`。
4. 线程 1、队列 2 时连续发布 10 个事件：3 个被接受，7 个在发布者线程抛出 `TaskRejectedException`，而 10 条报名都已保存。
5. 没有事务时发布：提交后监听器不执行。
6. 提交后监听器抛出异常：报名已提交，发布者没有收到异常，日志中只有一条 `SEVERE: TransactionSynchronization.afterCompletion threw exception`。
7. 带 `@Async` 方法的 Bean 是 CGLIB 代理，直接读代理对象的字段得到 `null`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21、spring-framework 7.0.9、junit 5.13.4。

## 三、执行步骤

`mvn test` 运行 `EventDeliveryTest` 的 7 个用例，每个用例新建容器；事实写入 `target/facts.tsv`，提交后监听器的日志从 Maven 输出中截取并去掉时间戳。

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/facts.tsv` | 每个用例记录的事实 |
| `evidence/after-commit-log.txt` | 提交后监听器失败时 Spring 输出的日志 |
| `evidence/test-results.txt` | 用例名与结果 |
| `evidence/maven-test.log` | Maven 输出 |
| `evidence/dependencies.txt` | 解析后的依赖版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 事务管理器不连接数据库，只用来观察事务同步的时机；真实数据源下提交与回滚的语义相同。
- 没有覆盖进程在提交后、监听器执行前崩溃的情况；那种情况下提交后监听器不会执行，只能靠 outbox 之类的持久化手段补上。
- `@TransactionalEventListener` 的 `fallbackExecution` 保持默认值 `false`。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
