# 验证记录：仲裁节点、w:majority 与回滚

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 配置 | 投票成员 3 个，存数据的 2 个；`getDefaultRWConcern` 没有 `defaultWriteConcern` 字段，来源 implicit |
| 两个数据节点都在 | `w:majority` 写入 a 成功 |
| 停掉 m2 | m1 仍是主节点 |
| `w:majority`（wtimeout=3000）写入 b | `WTimeoutError`，错误码 64 |
| 主节点上读 | local 读到 a、b；majority 读到 a |
| 默认写关注写入 c | 立即确认 |
| 停掉 m1、启动 m2 | m2 与 arb 选出 m2 为主节点；只有 a |
| 写入 d 后启动 m1 | m1 成为从节点，两个数据节点都是 a、d；b、c 被回滚 |
| rollback 目录 | m1 上有 1 个按集合存放的被回滚文档文件 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- MongoDB 9.0.4、8.3.13 与 8.0.34 的镜像在本机 Docker 虚拟机内核（7.0.12）上拒绝启动（内核与 TCMalloc 不兼容的启动检查，见 SERVER-121912），因此使用能启动的 8.2.12。副本集、写关注与回滚的规则在这些版本之间没有变化，但没有在 9.0 上验证。
- 停机是 `docker compose stop`，属于干净的关闭；断电、网络分区下的时序没有覆盖。
- 没有测量 majority 提交点停滞期间的缓存压力，这一点以官方文档为准。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-21 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
