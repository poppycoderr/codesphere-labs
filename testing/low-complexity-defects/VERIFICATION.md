# 验证记录：低复杂度不等于不需要测试

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 方法 | 圈复杂度 / NPath | 常见输入 | 边界输入 |
|---|---|---|---|
| `discounted` | 1 / 1 | 不打折时正确 | 打 9 折得到 0（期望 3598） |
| `total` | 1 / 1 | 小额正确 | 2000000 × 1500 溢出成 -1294967296 |
| `averageYuan` | 1 / 1 | 能整除时正确 | 1000 分 / 6 得到 1.66（期望 1.67） |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 三个错误是刻意构造的常见类型（整数除法顺序、溢出、截断），用来说明复杂度不衡量正确性，不代表低复杂度代码的缺陷率。
- 认知复杂度为 0 的方法 PMD 不报告，所以输出里只有圈复杂度与 NPath。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-21 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
