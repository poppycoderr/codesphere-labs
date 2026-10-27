# 验证记录：ORDER BY：索引方向、排序溢出与同值分页

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 升序索引上 ORDER BY category, score DESC | Using filesort，读 200002 条索引记录 |
| (category, score DESC) 索引 | 无 filesort，读 11 条，前 10 行与上一行相同 |
| category DESC, score DESC | 升序索引反向扫描，无 filesort |
| 无索引列排序取前 10 行 | 优先队列，32KB 缓冲区也不落盘 |
| 取最后 10 行，32KB 缓冲区 | 溢出 736 块，合并 121 趟；64MB 时不落盘 |
| ORDER BY price 翻 10 页 | 100 行里只有 54 个不同的 id；LIMIT 10 与 LIMIT 100 的前 10 行不同 |
| ORDER BY price, id 翻 10 页 | 100 个不同的 id |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 溢出块数、合并趟数与同值行的具体顺序取决于这份数据、这个版本的排序实现；换数据或版本后数字会变，现象（会溢出、会重复与遗漏）不变。
- 同值行的返回顺序是 MySQL 文档明确不保证的，本实验只说明它在这里确实变化了，不能推出它在别处一定变化。
- 单机容器 2 CPU、1 GB 内存；没有比较耗时。

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
