# 验证记录：撤销类命令各动了什么

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| reset --soft / 默认 / --hard HEAD~1 | HEAD=A；索引 v3 / v1 / v1；工作树 v4 / v4 / v1 |
| restore f / restore --staged f | 工作树 v4→v3 / 索引 v3→v2 |
| restore --source=HEAD~1 --staged --worktree f | 索引与工作树都是 v1，HEAD 不动 |
| checkout -- f / checkout HEAD~1 -- f | 同 restore f / 同上一行 |
| stash | 索引与工作树回到 v2 |
| revert | 工作树有修改时拒绝；新增 Revert 提交 |
| reflog | 找回提交 B，找不回 v4；克隆里没有原仓库的 reflog 与不可达提交；过期 + gc 后提交消失 |
| stash / -u / -a | 未跟踪文件留下 / 收走，被忽略文件留下 / 收走 |
| .gitignore 已跟踪文件 | 仍显示修改；rm --cached 后才取消跟踪 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖单文件、无冲突的情况；reset --merge、--keep 与合并冲突中的撤销没有涉及。
- reflog 过期用 --expire-unreachable=now 立即触发；默认的过期时间（可达 90 天、不可达 30 天）来自 Git 文档，没有等待。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-30 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
