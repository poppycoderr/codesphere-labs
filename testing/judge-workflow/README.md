# 判题结果的绑定与有界执行

对应文章：[judge-workflow.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/judge-workflow.md)。

在断网、只读根文件系统、非 root、去掉全部 capabilities 的容器里，用 `resource` 限制子进程，运行 8 个自己写的可信夹具，观察每种失败落在哪个限制上；再用一张 SQLite 表验证执行结果要绑定人工确认时的候选代码摘要与判据摘要。

## 快速运行

```bash
make verify     # 在加固的 Python 3.14.8 容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
