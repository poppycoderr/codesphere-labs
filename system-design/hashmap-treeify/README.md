# HashMap 冲突链的树化条件与查找代价

对应文章：[amortized-cost-and-tail-latency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/amortized-cost-and-tail-latency.md)。

`src/TreeifyLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行（`--add-opens java.base/java.util=ALL-UNNAMED`，用反射读取 `HashMap` 的内部表）。键的 `hashCode` 固定返回 42，全部落在同一个桶里；`equals` 调用一次计数一次，用调用次数衡量查找代价，输出确定。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
