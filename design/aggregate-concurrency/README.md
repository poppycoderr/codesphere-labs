# 200 个请求抢 100 个名额：四种聚合写法

对应文章：[从统一语言到限界上下文](https://github.com/poppycoderr/codesphere/blob/master/docs/design/ddd-from-language-to-boundaries.md)。

这是一个**轻量实验**：保留核心代码、一条验证入口、原始输出和简要验证记录，用来复现文章中的关键数字。

## 快速运行

```bash
make verify     # 需要 JDK 21+、Docker、Python 3；约 30 秒
make clean
```

容器使用实验专属的 compose 项目名，不暴露宿主机端口。
