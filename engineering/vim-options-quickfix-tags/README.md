# Vim 的局部选项、quickfix 与 tags

对应文章：[vim-options-quickfix-tags.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/vim-options-quickfix-tags.md)。

基于固定 digest 的 Debian trixie 构建镜像，安装 `vim-nox=2:9.1.1230-2` 与 `universal-ctags=5.9.20210829.0-2`。`src/run.sh` 在临时 HOME 里以 `vim -u NONE -es` 逐个运行场景：`set` 与 `setlocal` 对下一个缓冲区的影响、用户 ftplugin 里写 `set` 的后果、四个 Tab 相关选项组合下按 Tab 实际写入的字符、`errorformat` 对三种日志行的解析、未保存修改下 `:grep` 与 `:vimgrep` 的结果、ctags 条目在挪行与改名后的表现以及 `ctags -a` 是否删除旧条目。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（首次构建需下载软件包）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
