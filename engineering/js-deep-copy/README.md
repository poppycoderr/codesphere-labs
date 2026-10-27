# 深拷贝不是一个函数

对应文章：[js-deep-copy.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/js-deep-copy.md)。

`src/run.mjs` 用同一个包含 Date、Map、Set、NaN、undefined、访问器、Symbol 键、共享引用与环的对象，对比 structuredClone、JSON 往返与一个典型的手写递归深拷贝，并用一个带私有字段与校验的 Money 类说明领域对象的复制。

```bash
make verify     # 需要 Docker；约 5 秒
make evidence   # 重新采集 evidence/
make clean
```
