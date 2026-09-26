# JMM 可见性：停不下来的循环

对应文章：[jmm-and-happens-before.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/jmm-and-happens-before.md)。

单文件程序 `src/StopFlag.java`：主线程一秒后把停止标记设为 `true`，三秒后检查工作线程是否还活着。三种运行方式：

1. 默认参数，普通字段；
2. `-Xint`（只解释执行），普通字段；
3. 默认参数，`volatile` 字段。

## 快速运行

```bash
make verify     # 需要 JDK 21；三种方式各 3 次，约 40 秒
make evidence
make clean
```
