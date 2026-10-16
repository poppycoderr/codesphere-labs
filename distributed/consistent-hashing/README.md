# 一致性哈希的迁移、均衡与热点

对应文章：[consistent-hashing.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/consistent-hashing.md)。

`src/HashLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。10 万个键（`user:0` 到 `user:99999`），哈希函数取 MD5 的前 8 字节，所有输入固定。比较取模、哈希环（每个节点 1 个点）、虚拟节点（10、100、1000 个）、虚拟节点加有界负载（c=1.25、1.10）四类做法在 10 个节点上的负载分布，以及加入第 11 个节点后改变归属的键的比例；最后让一个键承担 20% 的请求，看它所在节点的请求占比。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
