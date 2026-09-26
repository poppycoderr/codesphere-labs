# Spring Boot 的默认异步执行器

对应文章：[thread-pool-sizing.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/thread-pool-sizing.md)。

只开启 `@EnableAsync`、不自定义任何 `Executor`，向一个会卡住的 `@Async` 方法提交 50 个任务：

| 测试 | 配置 |
|---|---|
| `DefaultExecutorTest` | 默认 |
| `VirtualThreadsExecutorTest` | `spring.threads.virtual.enabled=true` |

## 快速运行

```bash
make verify     # 需要 JDK 21 与 Maven；约 20 秒
make evidence
make clean
```
