# 金额的舍入与分摊

对应文章：[money-rounding-and-allocation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/money-rounding-and-allocation.md)。

`src/MoneyLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，全部是确定性计算。覆盖：`BigDecimal` 从 `double` 与从字符串构造的差别；把 2.675 保留两位的几种写法；浮点金额乘 100 转成分；七种舍入方式在正负半值上的结果与逐笔舍入的累计偏差；除法、`MathContext`、`stripTrailingZeros` 的输出；先舍入后求和与先求和后舍入；一笔钱拆给多方时各自舍入、最后一份拿余数、最大余数法三种做法；优惠分摊后分次退款；几种货币的小数位。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
