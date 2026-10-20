# 验证记录：计数器溢出与取模

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `Math.abs(Integer.MIN_VALUE)`、`Math.abs(Long.MIN_VALUE)` | 仍是各自的 MIN_VALUE（负数） |
| `Math.absExact(Integer.MIN_VALUE)` | ArithmeticException |
| `-7 % 3`、`Math.floorMod(-7, 3)` | -1、2 |
| 计数器越过 MAX_VALUE，`Math.abs(n) % 3` | 第三次取到下标 -2，ArrayIndexOutOfBoundsException |
| `Math.floorMod(n, 3)` | 不越界，溢出处连续两次选中 n1 |
| `(n & Integer.MAX_VALUE) % 3` | 不越界 |
| `"polygenelubricants".hashCode()` | -2147483648；`Math.abs(hash) % 10` 为 -8 |
| 10 万个键 `hashCode() % 16` 为负 | 940 个 |
| 每秒 1 万次自增溢出 int | 约 2.5 天（推算） |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 溢出时间是按固定速率的算术推算，不是测量。
- 负数取余的比例取决于键的分布，只说明「会出现」，不代表一般比例。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-20 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
