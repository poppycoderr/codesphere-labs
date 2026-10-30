# 推送与拉取的默认行为

对应文章：[git-push-pull-defaults.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/git-push-pull-defaults.md)。

`src/run.sh` 在 alpine/git 容器里建一个裸仓库作为远端，两个克隆 alice 与 bob 模拟两个人，依次验证：push.default 的默认行为与 push.autoSetupRemote；两边分歧时 pull 的默认报错与 --ff-only、--rebase、--no-rebase；amend 与 cherry-pick 的提交身份；--force-with-lease 在 fetch 前后的差别与 --force-if-includes。提交时间被固定以便输出确定，场景里需要不同时间时单独设置。

```bash
make verify     # 需要 Docker；约 5 秒
make evidence   # 重新采集 evidence/
make clean
```
