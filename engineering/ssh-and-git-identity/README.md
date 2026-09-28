# GitHub SSH 与多账号（离线验证）

对应文章：[github-ssh-key-on-mac.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/github-ssh-key-on-mac.md)。

只有一个脚本 `scripts/verify.sh`，全部离线完成，不连接 GitHub，不读写真实的 `~/.ssh` 与 `~/.gitconfig`：

1. 在 `build/tmp` 生成无口令的 Ed25519 与 RSA 4096 密钥（注释 `macbook-2026`），记录公钥大小与私钥权限，随后删除；
2. 用 `ssh -G -F <配置>` 打印两种 `ssh_config` 写法的最终配置；
3. 在临时 `HOME` 下按文章写 `~/.gitconfig` 与 `~/.gitconfig-work`，建两个仓库，读取 `user.email` 与远程地址；用只打印目标主机的 `GIT_SSH_COMMAND` 看 `git clone` 实际要连接的 Host。

## 快速运行

```bash
make verify     # 需要 OpenSSH、Git；几秒钟
make evidence
make clean
```
