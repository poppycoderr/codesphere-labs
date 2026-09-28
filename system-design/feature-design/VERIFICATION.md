# 验证记录：典型业务功能的几个实测

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **同分排序**：`ZADD 100 userA 100 userB 90 userC` 后 `ZREVRANGE` 得到 `userB 100, userA 100, userC 90`——同分按成员名逆字典序（`ZREVRANGE` 是字典序的逆序），与写入先后无关。用 `得分 × 10^11 + (10^11 − 提交时间后 11 位)` 编码后，先提交的 userA 排在 userB 前面，`score / 10^11` 取整还原出 100。2^53 约为 9.0 × 10^15，这种编码下得分上限约 90,072。
2. **百万成员排行榜**：100 万成员的 ZSET 为 `skiplist` 编码，`MEMORY USAGE` 74.1 MB。容器内 `redis-benchmark`：

| 命令 | 吞吐 | p50 |
|---|---:|---:|
| `ZREVRANGE rank:big 0 9 WITHSCORES`（前 10 名） | 145,773 次/秒 | 0.175 ms |
| `ZREVRANGE rank:big 500000 500009 WITHSCORES`（按名次翻到 50 万名） | 147,059 次/秒 | 0.175 ms |
| `ZREVRANGEBYSCORE rank:big +inf -inf WITHSCORES LIMIT 500000 10`（按分数带偏移） | 155,280 次/秒 | 0.167 ms |
| `ZREVRANK rank:big user:123456` | 203,252 次/秒 | 0.127 ms |

   `SLOWLOG` 记录的单条执行耗时：前 10 名 7 µs，按名次翻到 50 万名 9 µs，按分数带 50 万偏移 9 µs。在这个版本里，深翻页和取榜首是同一量级。
3. **GEO**：1 公里内 `店A 0.2790, 店B 0.7009`；10 公里内再加 `店C 8.6047`；`TYPE shops` 为 `zset`。
4. **拆红包**：100 元分给 10 人、模拟 100,000 轮：金额不等于总额的轮次 0；最小 0.01 元，最大 62.37 元；各位置平均：10.00  9.98  9.98  10.00  9.99  9.99  10.02  10.00  10.02  10.02。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/server.txt`](evidence/server.txt)。Redis 8.10.1（2 CPU、1 GB，不开持久化），`redis-benchmark` 在 Redis 容器内运行，没有网络开销。

## 三、执行步骤

见 `scripts/verify.sh`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 吞吐与 p50 受 `redis-benchmark` 和服务端共用 2 CPU 的影响，只看相对关系：按名次与按分数翻到 50 万名的吞吐都不低于取前 10 名的一半。
- 深翻页便宜只说明 Redis 8.10.1 的实现；其他存储（例如 MySQL 的 `LIMIT offset`）没有这个性质。
- 红包的随机数没有固定种子，各位置平均值每次略有不同；断言要求都在 10 ± 0.2 元内。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 是：「深度分页要先跳过 50 万个成员」在 Redis 8.10.1 上不成立，按名次或按分数带偏移都和取榜首同一量级；取前 10 名的吞吐与 p50、红包最大金额按本次证据更新 |
