# 验证记录：Vim 的编辑语言

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 命令 | 结果 |
|---|---|
| 光标在 quick 的 i 上：`dw` / `diw` / `daw` | `the qubrown fox` / `the  brown fox` / `the brown fox` |
| 光标在 b 上：`ci(` / `c2i(` | `f(a, g(X), d)` / `f(X)` |
| `A // checked` 后 `j.` 两次 | 三行都加上了注释 |
| 第 1 行 `yy`、第 2 行 `dd`、`p`、`"0p` | `p` 粘贴的是 trash，`"0p` 粘贴的是 keep |
| 交换逗号两侧的宏 `3@a` | `1,a|2,b|3,c` |
| 第 2 行没有逗号时 `3@a` | 第 2 行出错后停止，第 3 行未处理 |
| `:g/,/normal @a` | 只处理有逗号的两行 |
| `:g/^TODO/normal A (alice)` | 两行 TODO 被追加 |
| `:%s/\v(\w+)\s*\=\s*(\w+)/\2 = \1/` | `10 = width|20 = height` |
| `:%s/price: \zs\d\+/***/` | 只替换数字 |
| `:args *.txt | argdo %s/foo/bar/ge | update` | 两个 .txt 文件被修改，.md 不变 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 在 Ex 模式（`-es`）下执行，光标起始在最后一行，用例显式设置了光标位置；交互模式下的结果相同，但起始位置不同。
- 空配置下没有 `nocompatible` 以外的任何设置；个人配置（例如 `clipboard`、`iskeyword`）可能改变寄存器和单词的行为。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-17 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
