# Vim 的配置与插件怎样加载

对应文章：[vim-runtime-loading.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/vim-runtime-loading.md)。

与 `engineering/vim-editing-language` 使用同样的镜像（固定 digest 的 Debian trixie 加 `vim-nox` 2:9.1.1230-2）。`src/run.sh` 在一个临时 HOME 里搭出 `~/.vim` 的目录结构：vimrc、plugin、after/plugin、autoload、ftplugin、after/ftplugin，以及 `pack/my/start/foo` 和 `pack/my/opt/bar` 两个包。每个脚本被加载时往日志文件追加一行。每个场景启动一次 Vim（`--not-a-term -T dumb`，标准输入为空），执行命令后退出，输出加载日志与相关变量。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（首次构建需下载软件包）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
