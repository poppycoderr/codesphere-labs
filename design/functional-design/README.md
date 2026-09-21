# record 浅不可变、组合顺序、Stream 副作用与 Optional

对应文章：[Java 里的函数式设计](https://github.com/poppycoderr/codesphere/blob/master/docs/design/functional-design-in-java.md)。

这是一个**轻量实验**：保留核心代码、一条验证入口、原始输出和简要验证记录，用来复现文章中的关键数字。

## 快速运行

```bash
make verify     # 需要 JDK 21+；约 5 秒
make clean
```

不启动容器，不占用端口。
