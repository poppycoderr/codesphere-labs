# 合并结果没有被检查过

对应文章：[untested-merge-result.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/untested-merge-result.md)。

`src/scenario.sh` 用宿主机的 git 在临时目录里建三个小仓库，依次构造场景；检查用 `python3 -m unittest`。提交身份与时间固定，输出不含提交哈希与路径，结果是确定的，`scripts/verify.sh` 把它与预期逐行比较。

1. 两个分支各自通过检查、合并没有文本冲突，合并后的提交检查失败；用 `git merge-tree --write-tree` 预先算出合并结果并检查；先把目标分支合入功能分支再检查。
2. 撤销一个合并提交之后：功能分支没有新提交时再次合并、追加一个修复提交后再次合并、修复提交改动了被撤销的文件、先撤销那次撤销再合并。
3. 压缩合并之后目标分支又改了同一行，原分支继续开发后再次合并。

## 快速运行

```bash
make verify     # 需要 git 2.38 及以上与 python3；约 5 秒，不需要 Docker
make evidence
make clean
```
