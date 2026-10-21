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
| 再次停掉当前从节点 m1（仍有投票权） | writeMajorityCount=2，w:majority 写 e 超时，错误码 64；local 已读到 e |
| 正常 reconfig 把 m1 设为 votes:0/priority:0，仍保持停机 | writeMajorityCount=1，majorityVoteCount=2，投票成员 2 个、存数据的投票成员 1 个 |
| 连续三次显式 w:majority（wtimeout=3000） | f1、f2、f3 全部获确认；majority 读到 a、d、e、f1、f2、f3 |
| 旧初始化配置 electionTimeoutMillis=2000 | 被 9.0.4 拒绝，错误码 93；有效实验改为 4000，大于 2000 心跳间隔 |

## 二、环境

本轮 MongoDB 9.0.4、Docker 29.9.1、LinuxKit 7.0.14；Python 3.14.8、PyMongo 4.19.0、dnspython 2.9.0。服务端和客户端镜像均固定 digest，三个 MongoDB 容器各限 1 CPU/768 MiB，Python 客户端限 1 CPU/512 MiB；无宿主机端口。详见 [`evidence/environment.txt`](evidence/environment.txt)、`compose.yaml` 及 `evidence/client-versions.json`。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/lab.py`。原四阶段验证超时、默认写关注、切主与回滚；第五阶段在原场景结束后停掉当前从节点 m1，做同一停机状态下的投票配置前后对照。replSetReconfig 未使用 force，设置 maxTimeMS=15000。所有测试文档 ID 固定，单客户端顺序操作，不做性能采样。

写入门槛按两者较小值计算：全部投票成员的多数，以及存数据的投票成员总数。原 PSA 为 min(2,2)=2，调整后为 min(2,1)=1；选举多数仍为 2。详见[官方 write concern 计算](https://www.mongodb.com/docs/manual/reference/write-concern/#calculating-majority-for-write-concern)。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 8.2.12 旧证据保留在原固定 commit `37ffac03f4e5e33e47fc76f3b9050847d5b1e499`。本轮升级 Docker 内核后已实际验证 9.0.4；不能再写「9.0 未验证」，也不能由本轮验证推及其他补丁版本。
- votes:0/priority:0 降低写确认门槛，此时只有主节点一份在线数据。没有测试该状态下再次失去主节点、断电恢复、从节点重新加入或恢复投票权；不可宣称恢复冗余或已覆盖完整运维流程。
- 内核前置检查用于本次指定复验范围，不代表 MongoDB 仅支持 7.0.14 以上内核。CI 运行本实验也需提供该声明环境。
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
| 2026-10-21 | 首次建立（MongoDB 8.2.12，内核 7.0.12 上更新版本拒绝启动），脚本断言全部通过 | 新文章，结论取自本次证据 |
| 2026-10-21（Docker 升级后） | MongoDB 9.0.4 原场景与 votes:0 前后对照通过；旧选举配置被拒并改为 4000ms | 文章改为 9.0.4 的结论，补充 votes:0 的验证结果 |

[官方 PSA 缓解流程](https://www.mongodb.com/docs/manual/tutorial/mitigate-psa-performance-issues/)要求同时设置 votes:0 与 priority:0。重启/恢复从节点投票权应另按安全 PSA 重配置流程验证，本轮未运行。
