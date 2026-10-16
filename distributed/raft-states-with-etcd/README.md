# 写入、复制、提交、读取：etcd 三节点分区实测

对应文章：[raft-states-with-etcd.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/raft-states-with-etcd.md)。

`compose.yaml` 启动三个 etcd 3.7.2 节点（固定 digest）。每个节点同时连接两张 Docker 网络：节点之间只通过 `raft` 网络（别名 `eN-peer`）复制，客户端只通过 `client` 网络（别名 `eN`）访问。`scripts/verify.sh` 用 `docker network disconnect` 把某个节点从 `raft` 网络断开来制造分区，客户端仍然能连到它；读写由一次性的 `etcdctl` 容器执行（拨号超时 2 秒、命令超时 3 秒）。谁当选 leader 不确定，输出只使用「leader」「被隔离的 follower」「原 leader」这样的角色。

## 快速运行

```bash
make verify     # 需要 Docker；约 1—3 分钟（含等待选主与追平）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
