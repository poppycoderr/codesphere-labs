# JIT 与逃逸分析

对应文章：[jit-and-escape-analysis.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/jit-and-escape-analysis.md)。

`src/Escape.java` 里的方法都做同一件事：`new Point(x, y)`，再计算距离平方。每种条件单独启动一个 JVM，跑 40 批、每批 20 万次调用，用 `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` 计算每次调用分配的字节数，输出第 1 批与最后 10 批的中位数：

| 场景 | 点的去向 |
|---|---|
| `local` | 只在方法内使用 |
| `escape` | 写进静态字段 |
| `call` | 传给另一个静态方法 |
| `bimorphic` / `megamorphic` | 传给接口方法，调用点见过 2 种 / 4 种实现 |

另加三种 JVM 参数：`-XX:-DoEscapeAnalysis`、`-XX:CompileCommand=dontinline,Escape::consume`、`-Xint`。

## 快速运行

```bash
make verify     # 需要 JDK 21；约 30 秒
make evidence
make clean
```
