# 计数器溢出与取模

对应文章：[counter-overflow.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/counter-overflow.md)。

`src/Lab.java` 在固定 digest 的 temurin 25 容器里运行：`Math.abs` 与 `Math.absExact` 对 `MIN_VALUE` 的结果；Java `%` 对负数的结果与 `Math.floorMod`；一个从 `Integer.MAX_VALUE - 2` 开始的 `AtomicInteger` 轮询计数器分别用 `Math.abs(n) % 3`、`Math.floorMod(n, 3)`、`(n & Integer.MAX_VALUE) % 3` 选节点；`hashCode()` 恰好为 `Integer.MIN_VALUE` 的字符串；10 万个普通键的 `hashCode() % 16` 有多少为负；每秒 1 万次自增时溢出所需的时间（算术推算）。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
