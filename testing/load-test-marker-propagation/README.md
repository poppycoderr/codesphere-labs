# 压测标记的传播

对应文章：[load-test-marker-propagation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/load-test-marker-propagation.md)。

`src/MarkerLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行（`StructuredTaskScope` 在 JDK 25 仍是预览特性，需要 `--enable-preview`）。入口把「这是压测流量」记在当前线程的 `ThreadLocal` 上，订单写入处按标记决定写正式表还是影子表。逐个场景看标记是否还在：

1. 同一个线程；2. 线程池；3. `CompletableFuture.supplyAsync`；4. 定时任务；
5. `InheritableThreadLocal` 加线程池（压测请求先到、正常请求先到两种顺序）；
6. 消息（带头与不带头）；7. 缓存（共用键与加前缀）；
8. 提交时包装任务；9. `ScopedValue`；10. 写入处拒绝无标记的请求。

每个场景单独建线程池、顺序执行，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
