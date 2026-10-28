# 验证记录：ZooKeeper 会话不是 TCP 连接

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 断连 3 秒 | SUSPENDED → RECONNECTED，临时节点一直在，会话不变 |
| 断连 12 秒 | 临时节点 6 到 7 秒后消失；SUSPENDED → LOST → 新会话，节点不会重建 |
| close() 与进程消失 | 1 秒内消失 / 等到会话超时 |
| 锁持有者失联 | 另一客户端拿到锁时，原持有者只收到 SUSPENDED，isAcquiredInThisProcess() 仍为 true |
| 连续修改两次 | 一次性 watch 1 个事件，持久递归 watch 2 个 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 单节点 ZooKeeper，没有验证重连到其他节点与 sync。
- 断连由进程内代理模拟，不是真实的网络分区；服务端看到的是连接上不再有心跳。
- Curator 的 LOST 时刻取决于它的连接状态策略（默认在 SUSPENDED 之后再过一个会话超时）。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
