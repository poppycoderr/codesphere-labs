# 验证记录：Vim 脚本的作用域与重复加载

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 不在 augroup 里的 autocmd，source 3 次后保存 | 执行 3 次 |
| augroup + `autocmd!`，source 3 次后保存 | 执行 1 次 |
| 同一脚本里没有 `!` 的 function，再次 source | 不报错（同一脚本重新加载时允许替换） |
| 另一个脚本定义同名函数 | E122 |
| `s:secret`、`s:Get()` | 脚本外不可见；`g:shared` 可见 |
| a.txt 设 `b:mode` 后切到 b.txt | b.txt 里不存在，切回 a.txt 仍在 |
| `nnoremap d x` 后 `nmap D d$` | D 只删除一个字符（d 被再解释成 x） |
| `nnoremap D d$` | 删除到行尾 |
| `execute 'write ' . f`，f = `report#1.txt` | 写出 `reporta.txt.txt` |
| `fnameescape(f)` | 写出 `report#1.txt` |
| 作业运行 0.5 秒后以 3 退出，固定等 100 毫秒 | status=run，exitval=0 |
| 等到 status 不再是 run | status=dead，exitval=3 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 legacy Vim script；Vim9 script（`def`、`vim9script`）的作用域与重定义规则不同。
- 在 `-es` 模式下运行，作业回调（`exit_cb`）依赖主循环，本实验用 `job_status` 轮询加上限代替。
- 文件名展开只验证了 `#`；`%`、`<cword>` 等特殊字符同理，均由 `fnameescape` 处理。

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
