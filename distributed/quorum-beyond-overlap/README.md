# W+R>N 之后还缺什么：quorum 的确定性模拟

对应文章：[quorum-beyond-overlap.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/quorum-beyond-overlap.md)。

`src/QuorumLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。它不是真实的分布式存储，而是一个确定性的模拟：每个副本保存带时间戳或版本向量的值，每个场景的副本状态、哪些副本不可达、读写落在哪些副本上，都写在代码里。七个场景分别说明 W+R>N 这个条件本身没有保证的东西。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
