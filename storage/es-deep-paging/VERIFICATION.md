# 验证记录：Elasticsearch 翻到很深的页

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| from=9991,size=10 | HTTP 400 |
| 按 _id 排序 | HTTP 400，fielddata 默认禁用 |
| search_after 只按 score | 遗漏 2000 条 |
| search_after 按 score、pid | 12000 条完整 |
| 不用 PIT、翻页之间改 4 个文档 | 重复 2 条、遗漏 2 条 |
| 用 PIT | 快照固定，完整 |
| PIT 只按 score | 完整（_shard_doc 决胜） |
| 关闭 PIT | 释放 3 个上下文，再用 404 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 单节点、0 副本；多节点时 PIT 返回的 ID 可能在翻页过程中变化，本实验里没有变化。
- 没有测量深分页的耗时与内存，只验证了结果的正确性。
- scroll 没有覆盖。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
