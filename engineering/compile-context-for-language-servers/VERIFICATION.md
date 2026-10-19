# 验证记录：语言服务器看到的编译上下文

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 没有 `compile_commands.json` | 2 个错误：找不到 `config.h`，`MAX_ITEMS` 未声明 |
| CMake 生成的数据库（`-Iinclude`） | 0 个错误 |
| 构建改成 `-DUSE_V2=ON` | 构建失败：`v2_total` 未声明 |
| 仍用不含 `USE_V2` 的旧数据库 | 0 个错误 |
| 换成构建后的数据库（`-DUSE_V2 -Iinclude`） | 1 个错误，与构建一致 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 用 `clangd --check` 代替编辑器里的 LSP 客户端；编辑器还会发送未保存的缓冲区内容，这一点本实验没有覆盖。
- 只验证 C 与 clangd；其他语言服务器（Java、Python、TypeScript）读取项目配置的方式不同，但「分析用的配置要与构建一致」的结论相同。
- Debian 打包的 clangd 19 不是上游最新版本，`--check` 的输出格式可能随版本变化。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-19 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
