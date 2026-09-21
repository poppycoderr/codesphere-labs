# Spring 容器、Kafka Producer 与 JDK Stream 的模型切片

对应文章：[读懂一个系统的设计](https://github.com/poppycoderr/codesphere/blob/master/docs/design/reading-software-design.md)。

这是一个**轻量实验**：保留核心代码、一条验证入口、原始输出和简要验证记录，用来复现文章中的关键数字。

## 快速运行

```bash
make verify     # 需要 JDK 21+、Docker、Maven 3.9+、Python 3；约 40 秒（含 Kafka 启动）
make clean
```

容器使用实验专属的 compose 项目名，不暴露宿主机端口。
