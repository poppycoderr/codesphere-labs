# 失败路径上的写法

对应文章：[python-failure-paths.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/python-failure-paths.md)。

`src/failure_paths.py` 只用标准库，在固定 digest 的 Python 3.14.8 容器里分别以普通模式和 `python -O` 运行，输出与预期逐行比较。覆盖：用 `assert` 做参数校验在两种模式下的结果、`assert (条件, 消息)`、`finally` 里的 `return`、裸 `except` 与 `sys.exit`、原地重写文件与「临时文件加 `os.replace`」、`subprocess.run` 的返回码、线程池 `submit` 与 `map` 的异常。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
