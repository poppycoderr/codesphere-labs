# 公平锁的代价、条件队列与 tryLock 插队

对应文章：[从 AQS 看 ReentrantLock](https://github.com/poppycoderr/codesphere/blob/master/docs/java/aqs-and-locks.md)。

这是一个**轻量实验**：保留核心代码、一条验证入口、原始输出和简要验证记录，用来复现文章中的关键数字。

## 快速运行

```bash
make verify     # 需要 JDK 21+、Python 3；约 20 秒，占满 8 个线程
make clean
```

不启动容器，不占用端口。
