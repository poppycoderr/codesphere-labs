# 响应式流的信号契约

对应文章：[reactor-signal-contracts.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/reactor-signal-contracts.md)。

`src/Lab.java`（单文件，依赖见 `deps.txt`）依次验证：手写一个完成后还发元素和错误的发布者，看 Reactor 丢弃了什么；`take` 与 `map` 抛异常时的取消；`BaseSubscriber` 连续两次 `request`；`publishOn` 的预取与 `limitRate` 的补充；用 `StepVerifier.withVirtualTime` 比较 `flatMap` 与 `concatMap` 的输出顺序、同时活跃的内层数量和总耗时；`Mono.just`、`Mono.defer` 与未订阅的 `doOnNext` 何时执行。

```bash
make verify     # 需要 Docker；约 15 秒
make evidence   # 重新采集 evidence/
make clean
```
