# 超时与取消之后，任务是否停下

对应文章：[future-timeout-and-cancellation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/future-timeout-and-cancellation.md)。

`src/CancelLab.java` 是单文件程序，在固定 digest 的 temurin 25 容器里以源码方式运行。每个被测任务持续累加自己的 tick；发出超时或取消后，隔 200ms 再看 tick 是否还在增长，以此判断任务本身有没有停下，而不是只看 `Future` 的状态。任务分三种：响应中断、捕获后吞掉中断、忙循环不检查中断。

场景：

1. `Future.get(timeout)` 超时；
2. `Future.cancel(false/true)` 作用于三种任务；
3. `CompletableFuture.cancel(true)` 与 `orTimeout`；
4. `CompletableFuture.anyOf`：落选的任务，以及第一个完成的是失败时；
5. 请求登记表：只在调用方 `get` 超时，与 `orTimeout` + `whenComplete` 清理；
6. `shutdown()`、`shutdownNow()` 以及后者返回的任务对应的 `Future`；
7. `invokeAll` 带超时；
8. 平台线程与虚拟线程阻塞在 Socket 读上时的 `interrupt()`；
9. 结构化并发（JDK 25 预览 API，`--enable-preview`）：一个子任务失败后兄弟任务的状态，以及离开作用域的耗时。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 30 秒
make evidence
make clean
```
