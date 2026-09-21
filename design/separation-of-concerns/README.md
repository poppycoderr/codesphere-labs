# 关注点分离前后的测试对照

对应文章：[关注点分离与可测试性](https://github.com/poppycoderr/codesphere/blob/master/docs/design/separation-of-concerns-and-testability.md)。

这是一个**轻量实验**：保留核心代码、一条验证入口、原始输出和简要验证记录，用来复现文章中的关键数字。

## 快速运行

```bash
make verify     # 需要 JDK 21+、Docker、Python 3；约 40 秒
make clean
```

容器使用实验专属的 compose 项目名，不暴露宿主机端口。
