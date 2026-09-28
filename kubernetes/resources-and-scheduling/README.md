# Pending、OOMKilled、Evicted

对应文章：[resources-and-scheduling.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/resources-and-scheduling.md)。

`scripts/verify.sh` 按 `config/kind.yaml` 创建名为 `csl-rs` 的 kind 集群（Kubernetes 1.36.4，1 个控制面 + 1 个工作节点），结束时删除。Pod 由 `scripts/pod.py` 生成；`src/burn.py` 模拟间歇的计算任务并读取 cgroup 的 `cpu.stat`，`src/mem.py` 按 16MB 一块申请内存。

1. 按工作节点剩余可分配 CPU 放满各请求 2 核、只执行 `sleep` 的 Pod，再多放一个；记录调度结果、失败原因与节点实际 CPU 占用；
2. 不写 requests 的 Pod 在「已满」的节点上能否调度；
3. 值为 1000 的 PriorityClass，高优先级 Pod 请求 2 核时的抢占；
4. Guaranteed、Burstable、BestEffort 三种 Pod 的 QoS 与 `oom_score_adj`；
5. 同一组间歇任务（任务之间空闲 200ms）在不限、1 核、500m、250m 四种 CPU limit 下的耗时分位数、平均占用与限流周期数；
6. 内存 limit 128Mi 与 512Mi 下申请 320MB；
7. `limits.ephemeral-storage: 100Mi` 的 Pod 向可写层写 200MB。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 6 分钟
make evidence
make clean
```
