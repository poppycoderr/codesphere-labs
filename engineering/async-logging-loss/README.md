# 异步日志的丢失

对应文章：[async-logging-loss.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/async-logging-loss.md)。

`src/AsyncLogLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，用 Logback 1.5.38 的 `AsyncAppender`，以编程方式配置。它的下游是一个自己写的 appender，代替「写磁盘或发网络」：可以用闩锁卡住，也可以设定每条日志的固定耗时，并统计各级别实际写出的条数。这个 appender 的写入不响应中断，与普通的文件写入一致。

场景：默认配置的取值；下游卡住时连续记 INFO、再用 WARN 填满队列、再记一条 ERROR；`neverBlock = true`；`discardingThreshold = 0`；队列里还有 3000 条时关闭日志系统；默认与 `includeCallerData = true` 下拿到的调用方法名；子进程记 200 条日志后以三种方式结束。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 30 秒
make evidence
make clean
```
