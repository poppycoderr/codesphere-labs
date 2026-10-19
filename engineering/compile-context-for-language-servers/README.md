# 语言服务器看到的编译上下文

对应文章：[compile-context-for-language-servers.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/compile-context-for-language-servers.md)。

基于固定 digest 的 Debian trixie 构建镜像，安装 `clangd=1:19.0-63`（clangd 19.1.7）、`clang`、`cmake=3.31.6-2` 与 `make`。`src/proj/` 是一个最小的 C 项目：头文件在 `include/`，CMake 选项 `USE_V2` 打开时定义同名宏，代码在宏下调用一个不存在的函数。`src/run.sh` 用 `clangd --check` 输出诊断（与编辑器通过 LSP 得到的诊断来自同一套分析），依次比较：没有编译数据库、CMake 生成的数据库、构建改成 `-DUSE_V2=ON` 后的实际构建结果、仍用旧数据库与换成新数据库时的诊断。

## 快速运行

```bash
make verify     # 需要 Docker；约 2 分钟（首次构建需下载软件包）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
