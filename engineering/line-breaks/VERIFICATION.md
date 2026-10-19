# 验证记录：硬换行、软折行与渲染

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `tw=20`，默认 formatoptions，`gqq` 一行中文 | 仍是 1 行 |
| `formatoptions+=mM` | 断成 4 行 |
| 默认 `J` 合并两行中文 | 中间插入空格 |
| `formatoptions+=B` | 中文之间不插空格，英文照常插 |
| `set wrap linebreak` 后保存 | 文件不变 |
| `gggqG` 作用于说明加代码块 | 代码块的三行被并成一行 |
| 以 root 运行 | `modeline=0`，modeline 不生效 |
| `set modeline` | `textwidth=33` 生效 |
| modeline 设置 `foldexpr` | E992，选项不变 |
| markdown-it-py 渲染段落内换行 | 原样输出换行符 |
| Chromium 153、WebKit 26.6 | 汉字之间、英文之间的换行都显示成空格 |
| Firefox 155 | 汉字之间的换行不显示，英文之间显示成空格 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 浏览器结果用渲染宽度判断（与无空格、有空格两种写法相等即归类），字体为容器内的等宽字体；不同字体不改变是否插入空格。
- 站点使用的是 JavaScript 版 markdown-it，实验用的是 Python 移植版 markdown-it-py；两者都遵循 CommonMark，对软换行的输出相同。
- modeline 默认值与运行用户有关：Vim 对 root 默认关闭，普通用户默认开启；实验只在 root 下运行。

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
