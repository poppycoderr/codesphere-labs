# 仲裁节点、w:majority 与回滚

对应文章：[mongodb-arbiter-majority.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mongodb-arbiter-majority.md)。

三个 MongoDB 8.2.12 容器组成 PSA 副本集（两个数据节点 m1、m2，一个仲裁节点 arb，`electionTimeoutMillis=2000`）。verify.sh 在阶段之间停止和启动容器：停掉 m2 后分别用 `w:majority` 与默认写关注写入；再停掉 m1、启动 m2，在新主节点上写入；最后启动 m1，看它回滚了什么。

## 快速运行

```bash
make verify     # 启动三个 MongoDB 容器，分四个阶段在 Python 3.14.8 客户端容器里用 PyMongo 4.19.0 运行，阶段之间由宿主机停止和启动容器，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
