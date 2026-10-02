# 验证记录：失败路径上的写法

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

CPython 3.14.8。

1. 用 `assert amount > 0` 校验参数的 `withdraw(100, -50)`：普通模式抛 `AssertionError`；`python -O` 下返回 150，此时 `__debug__` 为 False。校验写成 `if … raise ValueError` 时两种模式都抛 `ValueError`。
2. `assert (1 > 2, 'msg')` 执行通过，编译时有 `SyntaxWarning`。
3. `try` 里抛异常、`finally` 里 `return 'ok'`：调用返回 `'ok'`，异常丢失；编译时有 `SyntaxWarning`。
4. `try` 里 `sys.exit(3)`：裸 `except` 把它拦住，进程没有退出；`except Exception` 拦不住，`SystemExit` 继续传播。
5. 原地重写配置文件、写到一半出错：文件内容变成 `{"version": 2, `。先写临时文件再 `os.replace`、同样写到一半出错：文件仍是原内容，没有残留临时文件；正常写完时文件变为新内容。
6. `subprocess.run(失败的命令)` 没有异常，`returncode=2`；加 `check=True` 抛 `CalledProcessError`。
7. 线程池 `submit` 后不取结果：离开 `with` 时没有异常，`future.exception()` 是 `ValueError`；`map` 后不遍历：没有异常，开始遍历时抛 `ValueError`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/failure_paths.py`。同一个脚本先以普通模式运行，再以 `python -O` 运行（只执行第 1 项）。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 结果是确定的。
- `finally` 里 `return` 的 `SyntaxWarning` 是 Python 3.14 新增的（PEP 765），更早的版本没有这个警告，行为相同。
- 原子替换的实验只模拟了「写入过程中抛异常」。进程被杀或断电时的保证还取决于 `fsync` 与文件系统，没有验证；`os.replace` 的原子性要求临时文件与目标在同一个文件系统。
- 没有覆盖 `asyncio` 里的异常，那部分见 `engineering/python-asyncio-task-lifecycle`。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
