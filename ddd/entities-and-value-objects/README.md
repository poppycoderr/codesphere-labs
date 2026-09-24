# 实体与值对象

对应文章：[entities-and-value-objects.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/entities-and-value-objects.md)。

活动报名案例中的 `Registration`（实体）与 `Money`、`Phone`、`Capacity`、`TimeSlot`（值对象），以及 `Counterexamples` 里几种看起来正确、实际有漏洞的写法。7 个 JUnit 用例把观察到的事实写入 `target/facts.tsv`；其中一个用例用 `javax.tools.JavaCompiler` 编译代码片段，记录类型化标识传反参数时的编译错误。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven
make evidence
make clean
```
