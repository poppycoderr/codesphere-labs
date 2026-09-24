# 验证记录：Redis 数据结构与编码

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. Redis 8.10.1 的默认阈值：`hash-max-listpack-entries = 512`、`hash-max-listpack-value = 64`、`zset-max-listpack-entries = 128`、`set-max-intset-entries = 512`、`set-max-listpack-entries = 128`、`list-max-listpack-size = -2`；超过阈值后分别变为 `hashtable`、`skiplist`、`hashtable`、`quicklist`。
2. 字符串值能否用 `embstr` 不是固定的 44 字节：Redis 8 把 key 与短值放进同一块内存，key 越长，值的上限越小。
3. Hash 从 500 个字段（listpack）到 600 个字段（hashtable），每个字段的内存开销增加约 3 倍；删回 500 个字段后编码仍是 `hashtable`。
4. 10 万条小对象，每 500 条合并为一个 Hash 比每条一个 String key 节省一半以上的内存；每 1000 条一个 Hash 超过阈值后收益明显缩小。
5. 一百万个连续 ID：Set 约 32 MB，Bitmap 约 131 KB，HyperLogLog 约 14 KB 且误差约 1%；在偏移量 1 亿处设置一位，Bitmap 会分配约 12.5 MB。
6. 删除 100 万元素的 Set，`DEL` 的服务端耗时是毫秒级，`UNLINK` 是微秒级；打开 `lazyfree-lazy-user-del` 后 `DEL` 与 `UNLINK` 相当。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)：Redis 8.10.1（`compose.yaml` 固定 digest，jemalloc 5.3.0），2 CPU、1 GB 内存，`--save '' --appendonly no`，其余为默认配置（[`evidence/config.tsv`](evidence/config.tsv)）。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 记录默认阈值与 lazyfree 相关配置。
2. 在各阈值两侧各写入一个对象，记录编码与 `MEMORY USAGE … SAMPLES 0`；对 3 种长度的 key，把值从 1 字节逐个加到 48 字节，记录编码与内存。
3. 写入 500 与 600 个字段的 Hash，再从 600 个字段中删掉 100 个。
4. 每种存法前执行 `FLUSHALL SYNC` 与 `MEMORY PURGE`，记录写入前后 `used_memory` 的差值：10 万个 String、每 500 条一个 Hash、每 1000 条一个 Hash；值均为 16 字节。
5. 写入 1 到 1,000,000 的 ID 到 Set、Bitmap、HyperLogLog，再在偏移量 1 亿处 `SETBIT`。
6. `slowlog-log-slower-than 0`，分别用 `DEL`、`UNLINK` 与打开 `lazyfree-lazy-user-del` 后的 `DEL` 删除 100 万个整数成员的 Set，各 5 次，从 `SLOWLOG` 读取服务端执行耗时。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/config.tsv`、`server.txt` | 默认阈值、lazyfree 配置、版本与分配器 |
| `evidence/encodings.tsv` | 阈值两侧的编码与内存 |
| `evidence/embstr-boundary.tsv` | 3 种 key 长度下，值 1—48 字节的编码与内存 |
| `evidence/hash-crossing.tsv` | Hash 500 / 600 / 删回 500 个字段 |
| `evidence/buckets.tsv` | 10 万条小对象三种存法的 `used_memory` |
| `evidence/counting.tsv`、`counting-membership.txt` | 三种计数结构与稀疏 Bitmap |
| `evidence/delete.tsv`、`delete-slowlog-sample.txt` | 15 次删除的服务端耗时与 SLOWLOG 原文 |
| `evidence/assertions.txt` | 断言结果 |

主要结果（`evidence/assertions.txt`）：

- 阈值与编码全部符合结论 1；`embstr` 的上限在 key 为 1、9、26 字节时分别是 40、32、15 字节。
- Hash 500 个字段 7,921 字节（15.8 字节/字段），600 个字段 28,562 字节（47.6 字节/字段）；删回 500 个字段后仍是 `hashtable`，25,162 字节。
- 10 万条小对象新增内存：String 7.05 MB，每 500 条一个 Hash 2.47 MB（节省 65%），每 1000 条一个 Hash 4.61 MB（节省 35%）。
- Set 32.3 MB、Bitmap 131 KB、HyperLogLog 14.4 KB（`PFCOUNT` 1,009,972，误差 1.00%）；稀疏 Bitmap 12.6 MB。
- `DEL` 中位数 37.1ms（5 次 36.9—41.2ms），`UNLINK` 12µs，`lazyfree-lazy-user-del yes` 时 `DEL` 12µs。

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/summarize.py`。

## 五、误差、限制与不能推出的结论

- `MEMORY USAGE` 与 `used_memory` 是分配器视角的字节数，不含碎片；RSS 见内存淘汰实验。
- 内存数字取决于 key 与值的长度，这里的 key 形如 `user:12345`、值为 16 字节，换成别的形态比例会变。
- `DEL` 耗时与 CPU、成员类型和数量有关；`UNLINK` 把释放交给后台线程，后台释放仍然消耗 CPU。
- `embstr` 的分界属于实现细节，只说明 Redis 8.10.1 的行为，不是配置项。
- HyperLogLog 的误差是这组连续 ID 的一次结果，标准误差约 0.81%。

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
| 2026-09-24 | 迁入本仓库，脚本化重跑 | 是：`embstr` 不是固定 44 字节；分桶节省 57% → 65%（原文为总量 7.4/3.2 MB，现按增量 7.05/2.47 MB）；Hash 500/600 字段 5,815/26,056 → 7,921/28,562 字节 |
