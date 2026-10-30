# 重复点击又生成了一次

对应文章：[generation-dedupe.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/generation-dedupe.md)。

`compose.yaml` 启动固定 digest 的 Redis 8.10.1；`src/lab.py` 用一个每次睡 0.3 秒、记录调用次数的假模型代替昂贵的生成调用，依次复现：先 GET 再生成再 SET 的重复生成；EXISTS 与 GET 之间键过期；状态与结果两个键各自过期；普通 SET 清掉 TTL 与 KEEPTTL；空答案被缓存。修正写法把一次生成记为一个哈希（pending / done / failed），用两个 Lua 脚本按幂等键抢租约、按持有者令牌写回结果。

```bash
make verify     # 需要 Docker；约 30 秒（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
