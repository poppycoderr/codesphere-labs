# 排序、选择与 Top K

对应文章：[sorting-selection-and-topk.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/sorting-selection-and-topk.md)。

单文件程序 `src/TopK.java`，数据是报名记录（分数、提交时间、ID），固定随机种子：

1. 用 `(int) (a.createdAt - b.createdAt)` 给跨度半年的记录排序，分别排 1 万条和 20 条；
2. 100 万条记录、分数只有 1,000 种取值，全排序、大小为 K 的堆、三路划分的快速选择各取前 100，只按分数比较与加上同分规则各一次；
3. N = 100 万、K 为 100、1 万、50 万时三种做法的耗时（预热 3 次，取 5 次中位数，并核对三者结果相同）；
4. 分片：按记录排名时各分片取前 100 再合并；按 key 聚合计数时各分片只上报本地前 2 或前 3。

## 快速运行

```bash
make verify     # 需要 JDK 21；约 1 分钟
make evidence
make clean
```
