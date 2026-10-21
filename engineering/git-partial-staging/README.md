# 工作区、暂存区与按块暂存

对应文章：[git-partial-staging.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/git-partial-staging.md)。

`src/run.sh` 在固定 digest 的 `alpine/git:v2.54.0` 容器里新建一个临时仓库，文件是 30 行文本。依次验证：两处修改用 `git add -p` 只暂存一处、提交的内容；相隔一行的两处修改被合成一个块与 `s` 拆分；暂存后再修改时提交的版本；只把第一个块的补丁交给 `git apply --cached`（编辑器 Git 插件按块暂存的做法）；`git restore -p` 丢弃一个块；`git reset --hard` 之后已暂存与未暂存的修改分别能否从 `git fsck --lost-found` 找回；先 `git stash` 再 `reset --hard` 的结果。

## 快速运行

```bash
make verify     # 需要 Docker；约 5 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
