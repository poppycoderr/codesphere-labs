# 仲裁节点、w:majority 与回滚

对应文章：[mongodb-arbiter-majority.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mongodb-arbiter-majority.md)。

三个 MongoDB 9.0.4 容器组成 PSA 副本集（两个数据节点 m1、m2，一个仲裁节点 arb，`electionTimeoutMillis=4000`）。verify.sh 在阶段之间停止和启动容器：停掉 m2 后分别用 `w:majority` 与默认写关注写入；再停掉 m1、启动 m2，在新主节点上写入；最后启动 m1，看它回滚了什么。

## 快速运行

```bash
make verify     # 启动三个 MongoDB 容器，分五个阶段在 Python 3.14.8 客户端容器里用 PyMongo 4.19.0、dnspython 2.9.0 运行，阶段之间由宿主机停止和启动容器，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。

## 9.0 复验与非投票从节点

需要 Docker Linux 内核至少 7.0.14；本轮为 7.0.14-linuxkit、Docker 29.9.1。每个 MongoDB 容器限 1 CPU/768 MiB，不映射宿主机端口，镜像使用固定 digest。

第五阶段在原回滚场景结束后停掉当前从节点 m1：先验证 majority 写入 e 超时，再正常 reconfig 为 votes:0/priority:0。m1 仍停机时连续三次 majority 写入成功，writeMajorityCount 从 2 变 1，majorityVoteCount 仍为 2。原始命令结果与最终容器状态见 evidence。

这降低了确认门槛，没有恢复第二份在线数据。实验没有测试恢复该节点投票权的运维流程。9.0.4 还拒绝选举超时等于心跳间隔的旧配置，已保留拒绝结果并使用 4000ms 选举超时。
