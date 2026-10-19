# 硬换行、软折行与渲染

对应文章：[line-breaks.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/line-breaks.md)。

分两部分。`src/vim.sh` 在基于固定 digest 的 Debian trixie 镜像（`vim-nox=2:9.1.1230-2`）里以 `vim -u NONE -es` 运行：`textwidth=20` 时 `gqq` 对一行中文的处理（默认与 `formatoptions+=mM`）、`J` 合并中英文行（默认与 `+=B`）、`set wrap linebreak` 后保存是否改文件、`gggqG` 对含代码块的文本、modeline 的默认值与 `foldexpr` 限制。`src/render.py` 在固定 digest 的 Playwright 1.63.0 镜像里用 markdown-it-py 4.2.0 渲染含软换行的段落，再用 Chromium、Firefox、WebKit 测量「中文⏎中文」与「abc⏎def」的渲染宽度，与无空格、有空格两种写法比较。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
