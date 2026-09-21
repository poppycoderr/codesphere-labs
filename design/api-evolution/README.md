# API 写法的错误暴露时机与 record 的二进制兼容

对应文章：[API 设计看错误何时暴露](https://github.com/poppycoderr/codesphere/blob/master/docs/design/expressive-java-api-and-dsl.md)。

这是一个**轻量实验**：保留核心代码、一条验证入口、原始输出和简要验证记录，用来复现文章中的关键数字。

## 快速运行

```bash
make verify     # 需要 JDK 21+；约 5 秒
make clean
```

不启动容器，不占用端口。
