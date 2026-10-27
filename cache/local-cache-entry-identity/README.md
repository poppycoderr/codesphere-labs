# 进程内缓存的条目身份

对应文章：[local-cache-entry-identity.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/local-cache-entry-identity.md)。

`src/Lab.java` 先用 ConcurrentHashMap 写出几种常见的手写缓存与在线登记表，逐项暴露按 key 删除、淘汰方向、并发加载、监听器异常与可变值的问题，再用 Caffeine 3.3.1（可控时钟）对照同样的场景。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
