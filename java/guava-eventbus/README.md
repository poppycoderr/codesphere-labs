# Guava EventBus 的四个行为

对应文章：[guava-eventbus.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/guava-eventbus.md)。

单文件程序 `src/EventBusBehavior.java`，Guava 33.5.0-jre：

1. 三个订阅者，中间一个抛异常，自定义 `SubscriberExceptionHandler`；
2. 发布一个没有订阅者的事件，另注册一个 `DeadEvent` 监听器；
3. 订阅者 sleep 200 ms，比较 `EventBus` 与 `AsyncEventBus` 的 `post` 返回耗时；
4. 注册一个订阅者后只保留弱引用，GC 前后看它是否存活，`unregister` 后再看一次。

## 快速运行

```bash
make verify     # 需要 JDK 21；几秒钟（首次需要下载 Guava）
make evidence
make clean
```
