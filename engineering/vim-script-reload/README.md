# Vim 脚本的作用域与重复加载

对应文章：[vim-script-reload.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/vim-script-reload.md)。

基于固定 digest 的 Debian trixie 构建镜像，安装 `vim-nox=2:9.1.1230-2`。`src/run.sh` 在临时 HOME 里以 `vim -u NONE -es` 逐个运行场景：同一份配置 source 三次后自动命令执行的次数（有无 augroup）、函数重定义、`s:` 与 `b:` 变量的可见范围、`nmap` 与 `nnoremap` 对右边按键的处理、`execute` 拼接含 `#` 的文件名、作业结束前后的 `job_status` 与 `exitval`。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（首次构建需下载软件包）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
