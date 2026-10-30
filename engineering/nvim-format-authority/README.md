# 保存时格式化，谁说了算

对应文章：[nvim-format-authority.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/nvim-format-authority.md)。

`scripts/verify.sh` 下载 Neovim 0.12.6 官方 Linux 包并按 SHA-256 校验，在 python:3.14.8-slim 容器里以 headless 方式运行 `src/lab.lua`。`src/fmt_server.py` 是只用标准库写的最小格式化语言服务（全量文档同步、整篇替换的格式化结果，可设置缩进宽度与响应延迟）；Lua 脚本给缓冲区挂一个或两个这样的服务，依次验证：不加过滤与按名字、filter 过滤；同步超时与迟到的结果；异步格式化期间用户修改；BufWritePre 中的同步与异步格式化；旧接口是否还在。

```bash
make verify     # 需要 Docker；约 20 秒（首次下载约 15 MB）
make evidence   # 重新采集 evidence/
make clean
```
