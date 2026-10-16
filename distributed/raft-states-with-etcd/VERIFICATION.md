# 验证记录：写入、复制、提交、读取：etcd 三节点分区实测

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 启动后 | 选出 1 个 leader，各节点 term 相同；写入 v1 后三个节点线性一致读都是 v1 |
| 隔离一个 follower，经由 leader 写入 v2 | 成功 |
| 被隔离的 follower：线性一致读 / 可串行读 / 写入 | 失败 / v1（旧值）/ 失败 |
| 另一个 follower 线性一致读 | v2 |
| 被隔离的 follower 的状态 | raft index 落后于 leader，报告 etcdserver: no leader |
| 恢复连接 | 追平，可串行读到 v2 |
| 把 leader 隔离到少数派 | 另外两个节点选出新 leader，term 增大；经由新 leader 写入 v3 成功 |
| 5 秒后原 leader | 不再认为自己是 leader，报告 no leader；写入与线性一致读失败，可串行读 v2 |
| 恢复连接 | 原 leader 读到 v3，当前 leader 仍是新 leader |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 分区只切断了复制网络，客户端到每个节点的连接始终正常；真实网络中客户端也可能被分区，表现取决于客户端的端点选择与重试。
- 「原 leader 下台」依赖 etcd 在 raft 层启用的 CheckQuorum；「恢复后不打断新 leader」依赖默认开启的 Pre-Vote。
- 选主耗时受选举超时的随机化影响，实验只断言是否发生，不记录时间。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-17 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
