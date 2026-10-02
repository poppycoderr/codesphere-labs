# 验证记录：时间边界与窗口函数的语义

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

MySQL 8.4.11。

1. **时间范围**（9 月实际 5 笔、合计 150；列类型 `DATETIME(3)`）：`BETWEEN '2026-09-01' AND '2026-09-30'` 得 3 笔 / 60；上界写成 `'2026-09-30 23:59:59'` 得 4 笔 / 100；上界写成 `'2026-10-01'` 得 6 笔 / 210；半开区间 `>= '2026-09-01' AND < '2026-10-01'` 得 5 笔 / 150；`DATE(created_at) BETWEEN …` 得 5 笔 / 150。
2. **执行计划**：半开区间为 `type=range`、`possible_keys=idx_created_at`；`DATE(created_at)` 为 `type=index`、`possible_keys=NULL`。
3. **会话时区**：同一条半开区间查询，在 `TIMESTAMP` 列上，会话时区 +00:00 得 5 笔 / 150，+08:00 得 4 笔 / 100；`DATETIME` 列在 +08:00 下仍为 5 笔 / 150。同一行在 +08:00 会话里，`TIMESTAMP` 列显示 `2026-10-01 07:59:59.500`，`DATETIME` 列显示 `2026-09-30 23:59:59.500`。
4. **LAG**（9 月 3 日没有记录）：直接 `LAG` 得 9 月 4 日为 -30（减的是 9 月 2 日）；判断相邻后为 NULL；补齐日历后 9 月 3 日为 -120、9 月 4 日为 +90。
5. **连续**：`COUNT(*) >= 3` 得 u1、u2、u3；按「日期减行号」分组后 `COUNT(*) >= 3` 得 u1、u3。
6. **排名**（90、90、80、80、70）：`RANK` 为 1、1、3、3、5；`DENSE_RANK` 为 1、1、2、2、3；`ROW_NUMBER`（加 id 排序）为 1—5。取「前 3」分别得到 4、5、3 行。
7. **窗口帧**：`SUM(amount) OVER (ORDER BY day)` 得 100、220、220、230；`ROWS UNBOUNDED PRECEDING` 得 100、150、220、230。`LAST_VALUE` 在默认帧下是当前行的值，帧扩到分区末尾后才是最后一行的值。
8. **ROLLUP**：`GROUP BY city WITH ROLLUP` 的结果里有两行 `city` 为 NULL（未知城市 30、总计 180），`GROUPING(city)` 分别为 0 和 1。
9. **每组最新**：按 `MAX(ts)` 回连得到 3 行（d1 两行）；`ROW_NUMBER() … ORDER BY ts DESC, id DESC` 取第一行得到 2 行。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/mysql-version.txt`](evidence/mysql-version.txt)。

## 三、执行步骤

见 `scripts/verify.sh`。数据在会话时区 +00:00 下写入；期望值与理由在 `schema/expected.tsv`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 结果是确定的，没有随机性。
- 表只有 6 行，执行计划只说明「能否形成范围」，不说明大表上的耗时；大表上的差别见 `storage/mysql-explain-plans`。
- 时区实验使用数值偏移（`+08:00`），没有覆盖带夏令时的命名时区。
- 默认窗口帧、`BETWEEN` 含两端、`ROLLUP` 的 NULL 是 SQL 标准语义；`WITH ROLLUP` 的写法与 `GROUPING()` 的可用性是 MySQL 8.4 的情况，其他数据库的语法不同。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，26 条查询全部与手算期望值一致 | 新文章，数字取自本次证据 |
