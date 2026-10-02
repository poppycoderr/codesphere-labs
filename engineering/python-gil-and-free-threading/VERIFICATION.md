# 验证记录：Python 线程能不能用上多核

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

CPython 3.14.8，容器限 4 个 CPU。「相当于几个核」= 线程数 × 单线程完成一份计算的用时 ÷ 多线程总用时。

| | 1 个线程完成 1 份 | 2 个线程 | 4 个线程 | 4 个进程 |
|---|---:|---:|---:|---:|
| 默认构建 | 121ms | 1.02 个核 | 1.01 个核 | 3.71 个核 |
| free-threaded 构建 | 143ms | 1.96 个核 | 3.43 个核 | — |
| free-threaded 构建 + `PYTHON_GIL=1` | 142ms | 1.08 个核 | 1.16 个核 | — |

- 4 个线程各阻塞等待 200ms：三种运行方式下用时都是 1 个线程的 0.99—1.04 倍。
- 4 个线程各做 20 万次不加锁的 `counter += 1`：默认构建与重新启用 GIL 时结果正好 800000；free-threaded 构建少于 800000。加锁后三种方式都是 800000。
- 4 个线程各向同一个 list `append` 10 万次：三种方式长度都是 400000。
- `sys._is_gil_enabled()`：默认构建 True；free-threaded 构建 False；设置 `PYTHON_GIL=1` 后 True。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/versions.txt`](evidence/versions.txt)。两种构建都是 uv 0.12.22 管理的 python-build-standalone 发行版（Clang 编译），不是 python.org 或官方 Docker 镜像里的构建。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/gil_bench.py`。每项用时取 5 次里最快的一次；基准（1 个线程）先预热一次，并同样放在新线程里运行。最初的版本在主线程里直接测基准、不预热，free-threaded 构建上基准偏慢，算出「2 个线程相当于 2.52 个核」这种超过线程数的结果，已修正并加了「并行度不超过线程数」的断言。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 连续 5 次运行：默认构建 4 线程 0.99—1.12 个核，free-threaded 3.22—3.59 个核，重新启用 GIL 后 1.09—1.18 个核；单线程用时比（free-threaded ÷ 默认）1.06—1.18。断言只检查小于 1.3、大于 2.5、小于 1.5。
- 单线程慢约 17% 只针对这个整数循环和这两个构建；官方文档给出的整体开销范围不同，不能用这一个数字代表。
- 默认构建上不加锁的计数这次没有丢失更新，这不是语言或实现的保证，只是当前版本切换线程的时机使这段代码没有被打断。
- 工作负载是纯 Python 字节码；调用会释放 GIL 的 C 扩展（如 NumPy 的大数组运算）时，默认构建的线程也能并行，没有在本实验中验证。
- 没有验证第三方扩展在 free-threaded 构建上的可用性，也没有验证导入不支持的扩展时 GIL 被自动重新启用的行为。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

两种构建缓存在 labs 的 `.cache/uv-python/`，删除该目录即可清理。

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
