# 判存结构与敏感词过滤

对应文章：[dedup-and-filter.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/dedup-and-filter.md)。

单文件程序 `src/DedupFilter.java`，内存用 JOL 0.17 按对象图统计：

1. 2000 万范围内随机标记 1000 万个整数，比较 `BitSet(20_000_000)` 与 `HashSet<Integer>`；并计算 40 亿个号码的位图与 `long[]` 大小；
2. 100 万个 URL，按 `m = -n·ln(p)/(ln2)²`、`k = (m/n)·ln2` 建布隆过滤器（双重哈希），目标误判率 1% 与 0.1%，用另外 100 万个未加入的 URL 统计误判，用已加入的统计漏判；同样的 URL 放进 `HashSet<String>` 的内存；
3. 1 万个 3—5 字的敏感词、20 万字文本（固定种子，平均每千字埋一个词），比较前缀树扫描与逐词 `indexOf`，统计所有出现位置，预热 2 次后取 5 次中位数。

## 快速运行

```bash
make verify     # 需要 JDK 21；约 2 分钟（首次需要下载 JOL）
make evidence
make clean
```
