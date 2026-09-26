# 线程池接收任务的顺序

对应文章：[thread-pool-sizing.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/thread-pool-sizing.md)。

单文件程序 `src/PoolOrder.java`：`corePoolSize=2`、`maximumPoolSize=4`、`ArrayBlockingQueue(2)`、`AbortPolicy`，提交 7 个被 `CountDownLatch` 卡住的任务，每次提交后打印线程数和队列长度。输出是确定的，与 `expected.txt` 逐行比较。

## 快速运行

```bash
make verify     # 需要 JDK 21，几秒
make evidence
make clean
```
