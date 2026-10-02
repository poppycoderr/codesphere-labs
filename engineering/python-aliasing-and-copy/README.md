# 名字绑定与对象别名

对应文章：[python-aliasing-and-copy.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/python-aliasing-and-copy.md)。

`src/aliasing.py` 只用标准库，是确定的单线程程序，在固定 digest 的 Python 3.14.8 容器里运行，输出与预期逐行比较。覆盖：可变默认参数、赋值与传参、`[[0] * 3] * 3` 与 `dict.fromkeys`、浅拷贝与深拷贝（含共享结构）、列表与元组上的 `+=`、闭包的延迟绑定、类属性、`dataclass` 的可变默认值、`==` 与 `is`。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
