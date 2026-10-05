# 浮点求和的顺序与精度

对应文章：[floating-point-summation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/floating-point-summation.md)。

`src/SumLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，全部是单线程的 IEEE 754 运算，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

1. 0.1 的实际取值、`0.1 + 0.2`、0.1 累加 10 次；
2. 大数吞掉小数：`1e16` 前后各加 10 个 1.0；加法不满足结合律；
3. `float` 累加器在 2^24 处停住；
4. 1000 万笔两位小数金额（固定种子）：逐个累加、Kahan、Neumaier、两两归并、`DoubleStream.sum()`、排序后累加、分成 1—16 块各自累加再合并，与 `BigDecimal` 精确和比较；用 `float` 累加；
5. 抵消：`[1, 1e100, 1, -1e100]`；
6. `long` 超过 2^53 之后经过 `double`；`BigDecimal` 的构造与相等；NaN 与正负零。

## 快速运行

```bash
make verify     # 需要 Docker；约 15 秒
make evidence
make clean
```
