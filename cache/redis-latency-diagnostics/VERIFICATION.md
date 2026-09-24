# 验证记录：Redis 延迟诊断

> 按 [验证标准](../../docs/verification-standard.md) 的十个部分记录。

## 一、待验证结论

1. 命令在主线程依次执行：`DEL` 百万成员的 Set、长 Lua、`KEYS` 执行期间，其他请求被推迟的时间与 SLOWLOG 记录的执行时间相当；`UNLINK` 不会。
2. 大量 key 集中过期会推迟其他请求，SLOWLOG 没有记录，LATENCY 的 `expire-cycle` 事件能看到；过期分散后推迟明显减小。
3. 由 save 规则触发的 fork 不出现在 SLOWLOG，只出现在 LATENCY 的 `fork` 事件中。
4. 慢订阅者的输出缓冲区持续增长，超过 `client-output-buffer-limit` 后被断开，SLOWLOG 与 LATENCY 都没有记录。
5. pipeline 批次越大吞吐越高，但在某个批次后不再增长，单批往返随批次增大。

## 二、适用版本与环境

- Redis 8.10.1（`compose.yaml` 固定 digest），2 CPU、2 GB，`--save '' --appendonly no --latency-monitor-threshold 1 --slowlog-log-slower-than 10000`，其余为默认（`evidence/config.tsv`）。
- 客户端：`src/Latency.java`（JDK 21，固定 digest，自带最小 RESP 客户端，`TCP_NODELAY`），与 Redis 共享网络命名空间，往返为回环地址。
- 运行环境见 `evidence/environment.txt`，镜像与资源见 `evidence/container.txt`。

## 三、场景与数据集

- 预先写入 100 万个 100 字节的 key（`KEYS` 与 fork 场景共用，约 160 MB）。
- 大 key：`big:set`，100 万个整数成员。
- 过期：50 万个 32 字节的 key，TTL 为 4 秒（集中，写入约 1 秒完成）或 4—14 秒（按序号分散）。
- Lua：`while i < 3000000 do i = i + 1 end`。
- 慢订阅者：订阅 `news` 后不读取，发布 3 万条 4 KB 消息。
- 所有数据由 `scripts/gen.py` 按序号生成。

## 四、执行步骤

1. 在容器内运行 `redis-cli --intrinsic-latency 5`。
2. 写入基础数据，重置 SLOWLOG、LATENCY 与统计，启动探测客户端。
3. 依次进入各阶段，每个阶段开始时用容器时钟记录时刻（`phases.tsv`）；造数阶段标记为 `load`，不参与统计。
4. fork 阶段用 `CONFIG SET save "1 1"` 加一次写入触发自动 `BGSAVE`，完成后恢复为空。
5. 慢订阅者阶段在容器内每 0.2 秒采样一次客户端相关指标。
6. 停止探测，导出 `SLOWLOG GET 256`、`LATENCY LATEST`、各事件的 `LATENCY HISTORY`、`LATENCY DOCTOR`、`INFO`。
7. 清空数据后运行 pipeline 测试：每种批次先预热一轮，再发送 10 万条 `SET` 计时。

## 五、原始证据索引

| 文件 | 内容 |
|---|---|
| `evidence/probe.tsv` | 每次探测：发出时刻（毫秒）、往返耗时（微秒）、相对计划时刻的推迟（微秒） |
| `evidence/phases.tsv` | 各阶段开始时刻（容器时钟） |
| `evidence/slowlog.json`、`latency-latest.json`、`latency-history.txt`、`latency-doctor.txt` | SLOWLOG 与 LATENCY 原始输出 |
| `evidence/slow-subscriber.tsv` | 慢订阅者阶段的客户端数、输出缓冲、客户端内存与断开次数 |
| `evidence/pipeline.tsv` | 各批次的吞吐与单批往返分位数 |
| `evidence/intrinsic-latency.txt`、`config.tsv`、`info-*.txt` | 基线、配置与结束时的 INFO |
| `evidence/assertions.txt` | 各阶段的分位数与断言结果 |

## 六、实际结果

| 结论 | 实际结果（`evidence/assertions.txt`） |
|---|---|
| 1 | `DEL` SLOWLOG 38.1ms，探测最多推迟 38.3ms；`UNLINK` 无 SLOWLOG，往返最多 0.4ms；`EVAL` 350.1ms / 349.7ms；`KEYS` 39.0ms / 38.8ms |
| 2 | 集中过期：推迟最多 26.6ms、p99 1,163µs，SLOWLOG 无记录，`expire-cycle` 最大 25ms；分散：最多 9.6ms、p99 762µs |
| 3 | `fork` 事件 1ms，SLOWLOG 无对应命令，探测最多推迟 2.4ms |
| 4 | 输出缓冲区采样最高 25.9 MB，断开 1 次，SLOWLOG 与 LATENCY 无记录 |
| 5 | 每批 1 / 10 / 100 / 1000 条：23,565 / 161,846 / 474,741 / 461,003 次/秒；单批 p50 43 / 63 / 206 / 2,164µs |

基线：探测 p50 49µs、p99 106µs；容器内 intrinsic latency 最大 5,061µs。

## 七、结果解释

- 探测请求与慢命令在同一个事件循环里排队，所以慢命令执行多久，其他请求就被推迟多久；等待被计入慢命令，不计入等待的请求自己的 SLOWLOG。
- 主动过期在事件循环的定时任务中进行，不属于任何命令；LATENCY 记录到的 `expire-cycle` 为 25ms，与探测的最大推迟一致。
- 慢订阅者的输出缓冲积压在服务器内存里，不占用事件循环的执行时间，所以对其他请求影响有限，风险在内存与被断开。
- pipeline 的收益来自减少往返；批次足够大后，瓶颈转到服务器执行与缓冲，吞吐不再增长。

## 八、误差、限制与不能推出的结论

- 「推迟」包含客户端自身（JVM、容器调度）的抖动，本次 intrinsic latency 最大约 5ms，几毫秒以内的差别不能归因到 Redis。
- fork 耗时与页表大小相关，160 MB 数据只有约 1ms，不能外推到大实例。
- 慢订阅者的输出缓冲是 0.2 秒一次的采样，峰值可能被漏掉；断开的直接原因以计数为准。
- 没有复现 swap、THP、网络带宽不足、`io-threads` 与持久化 fsync 慢的场景。
- 回环地址的往返远小于真实网络，pipeline 在跨机器时收益更大。

## 九、复现与清理命令

```bash
make verify     # 约 3 分钟，输出到 build/run
make evidence   # 重新采集 evidence/
make clean      # 删除容器
```

## 十、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
