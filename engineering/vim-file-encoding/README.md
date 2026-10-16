# Vim 打开中文文件乱码

对应文章：[vim-file-encoding.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/vim-file-encoding.md)。

与 `engineering/vim-editing-language` 使用同样的镜像（固定 digest 的 Debian trixie 加 `vim-nox` 2:9.1.1230-2）。`src/run.sh` 用 `iconv` 生成 GBK 文件，另有 UTF-8、带 BOM 的 UTF-8 文件；在空配置的 Vim 里用不同的 `fileencodings` 打开，输出 `encoding`、`fileencodings`、探测到的 `fileencoding` 与读到的第一行，以及保存后文件的字节。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（首次构建需下载软件包）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
