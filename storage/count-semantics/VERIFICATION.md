# 验证记录：count(*)、count(1) 与 count(列)

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| COUNT(*)、COUNT(1)、COUNT(id) | 都是 500000，都用 idx_status，耗时相差在 1.5 倍以内 |
| COUNT(coupon_id)（有索引，可为 NULL） | 166666，用 idx_coupon |
| COUNT(remark)（无索引，可为 NULL） | 100000，全表扫描，明显更慢 |
| TABLE_ROWS | 估算值，与真实行数相差在 10% 以内 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 耗时只比较相对关系（倍数阈值），绝对值随机器变化；单机容器 2 CPU、1 GB 内存。
- 选择哪个二级索引取决于索引大小与统计信息，本实验里 idx_status 最小。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
