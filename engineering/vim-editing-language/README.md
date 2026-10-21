# Vim 的编辑语言

对应文章：[vim-editing-language.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/vim-editing-language.md)。

`Dockerfile` 基于固定 digest 的 Debian trixie 安装版本固定的 `vim-nox`（2:9.1.1230-2）。`src/run.sh` 在空配置（`vim -Nu NONE -i NONE -n -es`）下对一组小文件执行编辑命令，输出执行后的文本（换行显示为 `|`）。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（首次构建需下载软件包）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
