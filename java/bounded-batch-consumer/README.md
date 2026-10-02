# 队列有界，不等于在途有界

对应文章：[bounded-batch-consumer.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/bounded-batch-consumer.md)。

`src/BatchLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。用预先装好的队列和闩锁构造确定的场景，不依赖吞吐量：

1. 队列积压 1000 条时，`drainTo(batch)` 与 `drainTo(batch, 99)` 得到的批数与最大批；
2. 容量 100 的队列，消费者把批交给异步写入线程（默认无界队列），下游卡住时生产者能放入多少；对照：用 `Semaphore` 限制 200 条在途；
3. 每 80ms 到达一条数据，批大小 50、最长等待 100ms：每次 `poll(100ms)` 重新计时，与按批的截止时间计算剩余等待；
4. 99 条好数据加 1 条坏数据：异常逃出循环、捕获后只记日志、整批失败后逐条重试并把坏数据放进死信；
5. 消费者已取出一批尚未写入时关闭：直接中断退出，与中断后排空并写完；
6. 毒丸：满队列上 `offer`，2 个消费者 1 颗毒丸，补到每个消费者一颗。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 20 秒
make evidence
make clean
```
