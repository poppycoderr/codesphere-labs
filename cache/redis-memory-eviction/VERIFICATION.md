# 验证记录：Redis 内存满了会怎样

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 默认 `maxmemory = 0`、`maxmemory-policy = noeviction`。
2. `maxmemory 8mb` 下写入 6 万条 200 字节的值：`noeviction` 与没有任何 key 带 TTL 的 `volatile-lru` 都有一半以上的写入返回 OOM、淘汰数为 0，之后读取仍正常；`allkeys-lru` 与 `allkeys-lfu` 写入全部成功。
3. `volatile-lru` 下，不带 TTL 的常驻数据全部保留，淘汰全部落在带 TTL 的缓存上。
4. 热 key 先被反复读取、之后遇到一次性写入的大量冷 key：`allkeys-lru` 的热 key 几乎全部被淘汰，`allkeys-lfu` 的热 key 全部保留。
5. 20 万个 key 同时到期后，已过期的 key 仍会在库里停留一段时间；TTL 分散后，删除分摊到整个区间。
6. 过期清完后 `used_memory` 很小而 RSS 不降，`mem_fragmentation_ratio` 在小数据集上被放大，需要结合 `allocator_frag_bytes` 等绝对值判断。
7. 删除大部分数据后 RSS 不下降；打开 `activedefrag` 后碎片字节与 RSS 下降，代价是持续的 CPU。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)：Redis 8.10.1（`compose.yaml` 固定 digest，jemalloc），2 CPU、1 GB 内存，`--save '' --appendonly no`；默认配置见 [`evidence/config-defaults.tsv`](evidence/config-defaults.tsv)。

## 三、执行步骤

`scripts/verify.sh` 依次执行（每个场景前 `FLUSHALL SYNC`、`MEMORY PURGE`、`CONFIG RESETSTAT`）：

1. 五种配置各写入 6 万条 200 字节的值（`volatile-lru-mixed`：先 1.5 万条不带 TTL，再 4.5 万条带 1 小时 TTL），记录 `--pipe` 的错误数、`evicted_keys`、`DBSIZE`、带 TTL 的 key 数、前 1.5 万个 key 的存活数，再写一条、读一条。
2. `allkeys-lru` 与 `allkeys-lfu`：写入 1000 个热 key 并各读 30 次，等 2 秒（LRU 时钟精度为 1 秒），再写入 6 万个冷 key，用 `EXISTS` 统计热 key 存活数。
3. 在容器内每 0.2 秒采集一次 `INFO keyspace`、`expired_keys`、`expired_stale_perc`、`used_memory`、RSS 与 `DBSIZE`，同时写入 20 万个 100 字节的 key：TTL 全部为 3 秒，或按序号分散到 3—9 秒；时间以开始写入为 0。
4. 写入 50 万个 64—448 字节的值，删除序号不能被 4 整除的 key，`active-defrag-ignore-bytes 10mb` 后打开 `activedefrag`，每秒采集一次碎片、RSS 与 CPU，共 40 秒。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/policies.tsv` | 五种配置的写入结果 |
| `evidence/scan-pollution.tsv` | 热 key 存活数 |
| `evidence/expiry-*.tsv`、`expiry-*-raw.txt`、`expiry-*-load.txt` | 过期时间序列、容器内采样原文、写入起止时间 |
| `evidence/memory-after-expiry.txt` | 过期清完后的内存与碎片指标 |
| `evidence/fragmentation-*.txt`、`defrag-timeline.tsv` | 写入、删除、整理后的 `INFO memory` 与整理过程 |
| `evidence/assertions.txt` | 断言结果 |

主要结果（`evidence/assertions.txt`）：

- `noeviction` 失败 34,588 次、库中 25,412 个；`volatile-lru`（无 TTL）失败 34,493 次；两者淘汰均为 0，之后写入返回 `OOM command not allowed`，读取正常。`allkeys-lru` / `allkeys-lfu` 淘汰约 3.6 万个，写入全部成功。
- `volatile-lru` 混合数据：常驻 15,000 条全部保留，4.5 万条缓存只剩 9,236 条。
- 热 key 存活：`allkeys-lru` 18 个，`allkeys-lfu` 1000 个。
- 同时过期：20 万个 key 0.47 秒写完，最后一个 key 到期后仍有最多 122,960 个已过期的 key 留在库里，约 1.1 秒后全部清除；`expired_stale_perc` 最高 75.6%。分散到 3—9 秒后删除持续约 6 秒。
- 过期清完：`used_memory` 1.58 MB，RSS 58.2 MB，`mem_fragmentation_ratio` 37.28，而 `allocator_frag_bytes` 只有 19.4 MB。
- 删除四分之三：`used_memory` 166 → 46 MB，RSS 196 MB 不变，碎片 128 MB；主动整理 40 秒后碎片 39 MB、RSS 107 MB，Redis 用户态 CPU 增加约 4 秒。

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/summarize.py`。

## 五、误差、限制与不能推出的结论

- 淘汰数与剩余 key 数每次相差几百，取决于采样淘汰的随机性。
- 过期清理速度取决于 CPU、`hz`、`active-expire-effort` 与 key 的数量，只说明 Redis 8.10.1 在 2 CPU 容器中的表现；与旧版本的差异不能归因到单一改动。
- 碎片与 RSS 取决于分配器、值大小分布和删除模式；RSS 的下降还受 jemalloc 归还内存的节奏影响。
- 主动整理的 CPU 开销只是这次的量级，`active-defrag-cycle-min/max` 会改变它。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 在 Redis 8.10.1 容器中实测，数字见文章 | — |
| 2026-09-24 | 迁入本仓库，脚本化重跑；过期改为容器内 0.2 秒采样，补充扫描污染、混合 TTL 与主动整理 | 是：过期回收从「第 10 秒才清完」改为到期后约 1 秒；碎片率需结合绝对字节；补充 LFU 抗扫描与主动整理实测 |
