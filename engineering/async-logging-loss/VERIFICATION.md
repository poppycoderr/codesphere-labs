# 验证记录：异步日志的丢失

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

默认配置：队列长度 256；丢弃阈值 51（剩余容量低于它时丢弃 TRACE、DEBUG、INFO）；`neverBlock` 为 false；关闭时最多等 1000 ms；不记录调用位置。

| 场景 | 结果 |
|---|---|
| 下游卡住期间记 1001 条 INFO | 全部立即返回；队列里 206 条，剩余容量 50 |
| 再记 50 条 WARN | 队列满（256 条） |
| 队列满后再记一条 ERROR | 业务线程 300 ms 内没有返回 |
| 下游恢复后实际写出的 | INFO 207、WARN 50、ERROR 1（记了 1001、50、1） |
| `neverBlock = true`，下游卡住期间记 300 条 WARN 再记 50 条 ERROR | 业务线程立即返回；写出 INFO 1、WARN 256、ERROR 0 |
| `discardingThreshold = 0`，队列满后再记一条 INFO | 业务线程被阻塞；258 条全部写出 |
| 队列里 3000 条、下游每条 2 ms 时关闭 | `stop()` 用了 1004 ms 返回，此时写出 504 条 |
| `maxFlushTime = 0`，500 条 | 写出 500 条 |
| 调用方法名 | 默认是 `?`；`includeCallerData = true` 时是 `main` |
| 记 200 条（下游每条 5 ms）后 `main` 直接返回 | 写出 0 条 |
| 同上，调用 `System.exit(0)` | 写出 0 条 |
| 同上，退出前先 `LoggerContext.stop()`（`maxFlushTime = 0`） | 200 条全部写出 |

实验过程中确认的一点：同一个 `LoggerContext` 调用过 `stop()` 之后，不重新 `start()`，再次调用 `stop()` 不会去停止后来挂上的 appender。实验在每个场景开始时重新 `start()`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 下游是模拟的 appender。真实的文件 appender 很少完全卡住，更常见的是变慢；网络 appender（发往日志收集端）在对端不可用时可以长时间卡住。
- 只验证了 Logback 1.5.38 的 `AsyncAppender`。Log4j2 的 AsyncLogger / AsyncAppender 有自己的队列满策略与默认值，本实验的数字不适用。
- 「写出」指下游 appender 的 `append` 执行完，不涉及操作系统页缓存到磁盘这一段。
- 子进程退出的三个场景里，直接返回与 `System.exit` 时写出的条数取决于退出前工作线程跑了多久，脚本只断言「不足 50 条」；归档的这一次是 0 条。
- Spring Boot 会注册关闭钩子来停止日志系统；没有这类钩子的独立程序才会出现「直接退出不写出」。实验没有覆盖 Spring Boot。

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
