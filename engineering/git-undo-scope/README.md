# 撤销类命令各动了什么

对应文章：[git-undo-scope.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/git-undo-scope.md)。

`src/run.sh` 在 alpine/git 容器里建一个起点仓库（提交 A、B，索引暂存 v3，工作树改成 v4），每条撤销类命令都在它的全新副本上执行，记录执行后 HEAD、索引与工作树中文件的内容；再分别验证 revert、reflog 找回与 gc 后消失、按正常传输协议克隆时 reflog 与不可达提交不会带过去、stash 与未跟踪和被忽略的文件、已跟踪文件写进 .gitignore。

```bash
make verify     # 需要 Docker；约 5 秒
make evidence   # 重新采集 evidence/
make clean
```
