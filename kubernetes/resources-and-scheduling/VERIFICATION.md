# 验证记录：Pending、OOMKilled、Evicted

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **调度看 requests**：工作节点可分配 6 核、已被请求 100m；3 个各请求 2 核的 `sleep` Pod，前两个 `Running`，第三个 `Pending`（`FailedScheduling ... 1 Insufficient cpu, 1 node(s) had untolerated taint(s)`）；此时工作节点容器的实际 CPU 占用为 2.60%。
2. **不写 requests**：同一节点上照样调度成功，QoS 为 `BestEffort`。
3. **抢占**：优先级 1000 的 Pod 请求 2 核后 `Running`，一个低优先级 Pod 被抢占（事件 `Preempted by pod ... on node csl-rs-worker`）并删除；原本 `Pending` 的 Pod 仍然 `Pending`。
4. **QoS**：Guaranteed `oom_score_adj=-997`；Burstable（只请求 64Mi 内存）996；BestEffort 1000。
5. **CPU limit**（每个任务约 60ms CPU，间隔 200ms，100 个任务）：

   | limit | p50 | p99 | 平均占用 | 被限流周期 |
   |---|---:|---:|---:|---:|
   | 不限 | 63ms | 74ms | 0.24 核 | 0/0 |
   | 1 核 | 63ms | 70ms | 0.24 核 | 0/261 |
   | 500m | 99ms | 118ms | 0.22 核 | 51/300 |
   | 250m | 285ms | 318ms | 0.17 核 | 257/463 |

   平均占用低于 limit 时仍被限流，延迟变长。
6. **内存 limit**：128Mi 下申请 320MB，60 秒内重启 3 次，上一次退出 `OOMKilled`、退出码 137；512Mi 下正常运行、0 次重启。
7. **临时存储**：写入 200MB 超过 100Mi 的 limit，15.9 秒后 Pod `Failed`，原因 `Evicted`，消息 `Pod ephemeral local storage usage exceeds the total limit of containers 100Mi.`；重启次数 0，容器退出码 137。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)、[`evidence/cluster-version.txt`](evidence/cluster-version.txt)。kind v0.33.0，节点镜像固定 digest；工作节点就是 Docker Desktop 虚拟机（6 核、约 15.6GiB），kind 没有配置 kube-reserved、system-reserved，所以可分配量等于容量。

## 三、执行步骤

见 `scripts/verify.sh`。任务的循环次数由不限 CPU 的标定 Pod 算出，目标约 40ms，实际在测量 Pod 里约 60ms（Python 循环速度在两次运行之间不同）；结论只依赖同一组任务在不同 limit 下的对比。调试时发现任务太轻（约 13ms）时 250m 几乎不限流，因此加大了单个任务的计算量。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 延迟数字取决于 CPU 型号与虚拟机负载，断言只检查相对关系：配额足够时 0 次限流；500m、250m 平均占用低于 limit 仍被限流；250m 的 p50 超过不限时的 3 倍。
- OOM 后的重启次数取决于退避时间与观察时长，断言只要求至少 2 次。
- 没有制造节点内存或磁盘压力，节点压力下的驱逐顺序以官方文档为准。
- 实际 CPU 占用用 `docker stats` 取工作节点容器的数值，是相对于整个 Docker 虚拟机的比例。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

中途中断时可执行 `.cache/bin/kind-v0.33.0 delete cluster --name csl-rs`。

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
