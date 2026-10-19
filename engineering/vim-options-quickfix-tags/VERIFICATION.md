# 验证记录：Vim 的局部选项、quickfix 与 tags

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| a.txt 里 `setlocal sw=2` 后打开 b.txt | b.txt 的 shiftwidth=8 |
| a.txt 里 `set sw=2` 后打开 b.txt | 2 |
| ftplugin/yaml.vim 里写 `set sw=2`，打开 yaml 后再打开 b.txt | b.txt 也是 2 |
| `expandtab sw=4 sts=4`，按 1、2 次 Tab | 4、8 个空格 |
| `noexpandtab ts=8 sts=4 sw=4`，按 1、2 次 Tab | 4 个空格；1 个制表符 |
| 默认 errorformat 解析 gcc 风格两行与自定义格式一行 | 前两行有效（含带空格的路径），第三行无效 |
| 加上 `ERROR [%f line %l] %m` | 3 条有效 |
| 删掉一行但不保存 | `:grep` 找到 1 处，`:vimgrep` 找到 0 处 |
| tags 条目 | 搜索模式 `/^int total(int n) ...$/` |
| 文件开头插入 3 行、不重建 | `:tag total` 仍跳到正确的第 5 行 |
| 改名为 grand_total、不重建 | `E434: Can't find tag pattern` |
| `ctags -a` 追加 | 旧的 total 条目仍在 |
| 重新生成 | total 0 条，grand_total 1 条 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 Vim 9.1 与 Debian 打包的 Universal Ctags 5.9（上游当前版本更新，但条目格式与 `-a` 的语义相同，引用前请对照上游文档）。
- 在 `-es` 模式下运行，不涉及屏幕显示；Tab 的显示宽度由 `tabstop` 决定，本实验只看写入文件的字符。
- `:grep` 使用外部 grep，`grepprg` 在实验里显式设置。

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
