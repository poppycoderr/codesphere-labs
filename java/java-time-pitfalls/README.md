# java.time 的边界

对应文章：[java-time-pitfalls.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/java-time-pitfalls.md)。

`src/TimeLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，全部使用固定的日期与显式的时区，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

1. 同一个时间点在不同时区的本地日期；同一个本地时间按不同时区解释；
2. 纽约 2026-03-08 进入夏令时前后：加 1 天与加 24 小时、不存在的本地时间、出现两次的本地时间、这一天的长度；
3. 月末加月份、`Period` 的天数分量与总天数、用 `int` 计算毫秒数的溢出；
4. 格式化字母 `YYYY`、`hh`、`DD`；
5. 默认（SMART）解析与 STRICT 解析，`yyyy` 与 `uuuu`；
6. `ZonedDateTime` 的 `equals` 与 `isEqual`；`new Date(2026, 10, 9)`。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
