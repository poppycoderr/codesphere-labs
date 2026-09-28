# 验证记录：缓存分层的访问延迟

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 层 | 访问延迟 |
|---|---|
| Caffeine 本地缓存 | 平均 87.3 ns |
| Redis `GET` | p50 0.049 ms，p99 0.068 ms |
| MySQL 主键查询 | p50 0.071 ms，p99 0.102 ms |

本地缓存与 Redis 相差约 560 倍，Redis 与 MySQL 只差 1.4 倍：引入 Redis 主要是为了给数据库分担读流量，而不是让单次读取快很多；真正把延迟降一个量级的是本地缓存。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。Redis 与 MySQL 容器各 2 CPU、1 GB，客户端与它们在同一台机器的 Docker 网络里。

## 三、执行步骤

见 `scripts/verify.sh`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 三者都在同一台机器上，Redis 与 MySQL 的往返只经过 Docker 网络，没有跨主机的网络延迟；跨主机部署时两者都要再加上一次网络往返，差距会进一步缩小。
- MySQL 的数据全部在 Buffer Pool 里；数据不在内存时，主键查询会慢得多。
- 断言只检查相对关系：本地缓存低于 1 µs 且比 Redis 快 50 倍以上；MySQL 慢于 Redis；Redis 与 MySQL 的差距小于本地缓存与 Redis 的差距。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 是：Redis p50 0.12 ms、MySQL 0.24 ms 改为 0.049 ms、0.071 ms，结论不变 |
