# 验证记录：合并结果没有被检查过

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 分支 rename（把函数 `total` 改名为 `subtotal` 并改掉当时全部调用方）上的检查通过；分支 invoice（从同一个起点新增一个调用 `total` 的文件）上的检查通过。
2. main 合入 rename 之后，预先计算 main 与 invoice 的合并结果：无文本冲突；在这个结果上运行检查失败（`AttributeError: module 'pricing' has no attribute 'total'`）。把最新的 main 合入 invoice 分支后在分支上检查，同样失败。直接合并：文本冲突 0 处，合并提交生成，main 上的检查失败。
3. 合并 feature（两个提交，新增 `feature.txt`）之后用 `git revert -m 1` 撤销合并提交，`feature.txt` 消失。feature 没有新提交时再次合并，输出 `Already up to date.`。
4. feature 追加一个修复提交（新文件 `feature_fix.txt`）后再次合并：成功，main 上有 `feature_fix.txt`、没有 `feature.txt`。再追加一个修改 `feature.txt` 的提交后合并：冲突（`DU feature.txt`）。
5. 先撤销那次撤销，再合并 feature：`feature.txt` 三行内容与 `feature_fix.txt` 都在。
6. 压缩合并 topic 之后 main 又改了同一行，topic 继续开发后再合并：`notes.txt` 冲突；`git branch --merged main` 不列出 topic。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。git 2.55.0，Python 3.11.9（只用标准库的 unittest）。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 场景是人为构造的最小例子，说明这几种情况会发生，不说明它们在真实仓库里出现的频率。
- 「检查」是三四行的单元测试；真实项目里语义冲突还可能表现为编译失败、数据库迁移序号重复、配置键冲突等，机制相同。
- 没有接任何代码托管平台。平台的合并队列、合并前检查针对哪个提交运行，只在文章里引用各自的文档。
- 合并策略用的是 git 2.55.0 的默认策略（ort）；冲突的具体表现（如 `DU`）随策略与版本可能不同。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-10 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
