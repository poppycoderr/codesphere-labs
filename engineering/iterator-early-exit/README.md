# 提前结束也是迭代协议的一部分

对应文章：[iterator-early-exit.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/iterator-early-exit.md)。

`src/run.mjs` 用一个模拟分页读取、打开时占用「连接」的迭代器，逐项观察 break、throw、解构、展开对 return() 的调用，生成器 finally 的执行时机，异步迭代与可读流，以及类数组和迭代中修改数组。

```bash
make verify     # 需要 Docker；约 5 秒
make evidence   # 重新采集 evidence/
make clean
```
