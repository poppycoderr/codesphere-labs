# 验证记录：工作区、暂存区与按块暂存

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 改第 3 行与第 25 行 | 2 个块 |
| `add -p` 回答 y、n | 暂存区 1 个块（第 3 行），工作区 1 个块（第 25 行） |
| 提交 | 只含第 3 行；第 25 行仍在工作区 |
| 改第 10 行与第 12 行 | 1 个块；`s` 拆成 2 个 |
| 暂存 v1 后改成 v2 再提交 | 提交里是 v1，工作区是 v2 |
| 第一个块的补丁交给 `git apply --cached` | 暂存区 1 个块，工作区 1 个块 |
| `restore -p` 丢弃第一个块 | 第 7 行恢复原样，第 28 行保留 |
| 暂存一处、未暂存一处后 `reset --hard` | 两处都消失 |
| `git fsck --lost-found` | 只找回已暂存的那一处 |
| 先 stash 再 `reset --hard`，然后 pop | 修改还在 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 Git 2.54.0 命令行；编辑器插件（如 Vim 的 fugitive、gitgutter）的命令随版本变化，本实验只模拟它们底层使用的 `git apply --cached`。
- 悬空对象会被 `git gc` 按 `gc.pruneExpire`（默认两周）清理，找回只在这之前有效。
- 块的划分取决于 diff 的上下文行数（默认 3 行），本实验使用默认值。

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
