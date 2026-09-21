# InnoDB 行锁的范围：data_locks 与阻塞探测

对应文章：[InnoDB 行锁锁的是什么](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/innodb-locking-ranges.md)。

这是一个**轻量实验**：保留核心代码、一条验证入口、原始输出和简要验证记录，用来复现文章中的关键数字。

## 快速运行

```bash
make verify     # 需要 JDK 21+、Docker；约 60 秒
make clean
```

容器使用实验专属的 compose 项目名，不暴露宿主机端口。
