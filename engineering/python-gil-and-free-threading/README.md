# Python 线程能不能用上多核

对应文章：[python-gil-and-free-threading.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/python-gil-and-free-threading.md)。

`src/gil_bench.py` 只用标准库。`scripts/verify.sh` 在固定 digest 的 uv 容器里（限 4 个 CPU）用 `uv python install` 取得 CPython 3.14.8 的默认构建与 free-threaded 构建（同一来源，下载到 labs 缓存目录），用同一份代码分别运行三次：

- `gil`：默认构建；
- `ft`：free-threaded 构建；
- `ft_gil_on`：free-threaded 构建，设置环境变量 `PYTHON_GIL=1` 重新启用 GIL。

每次测量：1、2、4 个线程各完成一份纯 Python 计算的用时；4 个线程各阻塞等待 200ms；4 个进程各完成一份计算（只在默认构建上）；4 个线程不加锁、加锁做 `counter += 1`；4 个线程向同一个 list `append`。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；首次约 1 分钟（下载两种构建），之后约 20 秒
make evidence
make clean
```
