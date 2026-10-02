# 验证记录：超时与取消之后，任务是否停下

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.4。「任务仍在运行」指发出超时或取消 200ms 后，任务的 tick 仍在增长。

1. `Future.get(100ms)` 抛 `TimeoutException`，`isDone=false`、`isCancelled=false`，任务仍在运行。
2. `Future.cancel`：
   - `cancel(false)`：返回 true，`isDone`、`isCancelled` 都为 true，`get` 抛 `CancellationException`，任务仍在运行；
   - `cancel(true)` 且任务响应中断：任务被中断后退出；
   - `cancel(true)` 且任务吞掉中断或不检查中断：`Future` 状态同上，任务仍在运行。
3. `CompletableFuture.cancel(true)`：`isCancelled=true`，任务仍在运行；`orTimeout(100ms)` 之后 `get` 得到 `ExecutionException: TimeoutException`，任务仍在运行。
4. `anyOf`：返回先完成的结果，落选的任务仍在运行；一个任务先失败、另一个 200ms 后成功时，`anyOf` 的结果是那个失败。
5. 请求登记表：只在调用方 `get` 超时，登记项残留 1 项，迟到的响应 `complete` 返回 true；用 `orTimeout` + `whenComplete` 做比较删除后登记项为 0，迟到的响应找不到登记项，对已超时的 `Future` 再 `complete` 返回 false。
6. `shutdown()` 0ms 返回，`awaitTermination(200ms)=false`，运行中的任务仍在运行；`shutdownNow()` 返回 1 个未开始的任务，运行中的任务被中断后退出（其 `Future` 为 `ExecutionException: InterruptedException`），排队任务的 `Future` 在 `get` 时超时、`isDone=false`；对返回的任务逐个 `cancel` 后才变为 `CancellationException`。
   线程 1、队列 1 都占满后再提交，`DiscardPolicy` 丢弃的任务，其 `Future` 在 `get` 时超时，`isDone=false`、`isCancelled=false`。
7. `invokeAll(…, 100ms)`：两个任务的 `Future` 都是已取消；响应中断的任务停止，不检查中断的任务仍在运行。
8. Socket 读：平台线程 `interrupt()` 后仍阻塞，对端关闭后 `read` 返回 -1；虚拟线程 `interrupt()` 后 `read` 抛 `SocketException（Closed by interrupt）`，线程结束。
9. 结构化并发：一个子任务失败后 `join` 抛 `FailedException`；兄弟任务响应中断时 53ms 离开作用域，不检查中断时作用域等到它自己跑完，共 1000ms。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。`eclipse-temurin:25-jdk` 固定 digest；结构化并发在 JDK 25 是预览 API（JEP 505），运行时加 `--enable-preview`。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/CancelLab.java`。任务开始由 `CountDownLatch` 确认后才发出超时或取消，避免「任务还没开始就被取消」的分支。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 「仍在运行」是 200ms 窗口内的观察；被测任务最长运行 1.5—5 秒，之后会自行结束。
- 离开作用域的耗时（53ms、1000ms）随调度有小幅波动，断言只检查小于 400ms 与不小于 900ms。
- 结构化并发是预览 API，后续版本可能变化；结论只对 JDK 25.0.4 成立。
- Socket 读的结论针对 `java.net.Socket` 的默认实现；NIO 的 `SocketChannel` 是可中断通道，行为不同，没有在本实验中验证。
- 没有覆盖第三方执行器与框架（Spring `@Async`、Netty、gRPC）的取消传播。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
