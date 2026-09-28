# 验证记录：Guava EventBus 的四个行为

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **异常隔离**：订阅者 B 抛 `IllegalStateException`，A 与 C 照常收到 `[A-created-1001]`、`[C-created-1001]`；异常交给 `SubscriberExceptionHandler`（1 个），`post` 不向调用方抛出。
2. **DeadEvent**：发布没有订阅者的事件，`DeadEvent` 监听器收到 `[NoSubscriber[x=hello]]`。
3. **默认同步**：订阅者 sleep 200 ms，`EventBus.post` 返回耗时 202 ms，`AsyncEventBus.post` 0 ms。
4. **注册关系是强引用**：注册后把局部变量置为 null、多次 GC，对象仍然存活；`unregister` 后再 GC，对象被回收。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。宿主机 JDK 21.0.5，Guava 33.5.0-jre（failureaccess 1.0.3）。

## 三、执行步骤

`scripts/verify.sh`：下载 Guava，运行 `src/EventBusBehavior.java`，断言输出。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 同步投递的耗时断言只要求 `post` 不少于 200 ms、异步 `post` 少于 50 ms。
- 强引用的验证依赖 `System.gc()` 真正触发回收；程序每次调用 5 次并间隔 50 ms。
- 只测了 `EventBus` 与 `AsyncEventBus`，没有覆盖订阅方法上的 `@AllowConcurrentEvents`。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 是：同步 post 耗时由 216 ms 改为 202 ms、异步由 4 ms 改为 0 ms，订阅者名称与输出格式按证据调整 |
