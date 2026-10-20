# 追踪上下文在哪一跳丢失

对应文章：[trace-context-propagation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/trace-context-propagation.md)。

`src/Lab.java` 在固定 digest 的 temurin 25 容器里运行，依赖由 `pom.xml` 固定（OpenTelemetry Java SDK 1.67.0 与测试用的内存导出器）。在一个父 span 的作用域里，把创建子 span 的任务分别放在同一线程、普通线程池、`Context.taskWrapping` 包装的线程池、`CompletableFuture.runAsync`（公共池与包装后的线程池）、虚拟线程、`Context.current().wrap(task)` 中执行，比较子 span 与父 span 是否属于同一条 trace。再在进程内启动 JDK `HttpServer`，服务端用 W3C 传播器从请求头提取上下文后创建 server span，客户端分别不注入与注入 `traceparent`；最后用 `parentBased(alwaysOff)` 采样器检查不采样时传播的标志位，以及没有 `end()` 的 span 是否被导出。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
