# 验证记录：节点 NotReady

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **启动链**：控制面节点上 containerd、kubelet 由 systemd 运行；kubelet 的 `staticPodPath` 是 `/etc/kubernetes/manifests`，其中有 etcd、kube-apiserver、kube-controller-manager、kube-scheduler 四个清单；kube-apiserver Pod 的 `kubernetes.io/config.source` 为 `file`，`hostNetwork=true`。
2. **没有 CNI**：`/etc/cni/net.d` 为空，`/opt/cni/bin` 有 `host-local loopback portmap ptp`。节点 `False KubeletNotReady`，消息含 `cni plugin not initialized`；污点 `node.kubernetes.io/not-ready:NoSchedule`。静态 Pod 与 kube-proxy `Running`，CoreDNS `Pending`。普通 Pod `Pending`（`FailedScheduling ... untolerated taint(s)`）；容忍该污点的 Pod 被放到节点上，卡在 `ContainerCreating`（`NetworkNotReady`）。普通 Pod 自动获得 `not-ready`、`unreachable` 各 300 秒的容忍。
3. **就绪按节点判断**：只给控制面节点写 CNI 配置，0.3 秒后它就绪，工作节点仍为 `False KubeletNotReady`；CoreDNS 调度到控制面节点并运行。
4. **Ready 不代表跨节点可达**：三个节点都写入配置后全部 `Ready`，工作节点上的 Pod `Running`，但直连控制面节点上的 CoreDNS Pod 超时、DNS 解析 `connection timed out`；工作节点路由表中没有其他节点的 Pod 网段。各节点补上路由后，直连返回 `OK`，解析出 `10.96.0.1`。
5. **kubelet 停止**（Deployment 容忍 `unreachable`、`not-ready` 20 秒）：51.5 秒后节点变为 `Unknown`（`NodeStatusUnknown: Kubelet stopped posting node status.`），加上 `unreachable` 的 `NoSchedule` 与 `NoExecute` 污点；71.7 秒旧 Pod 被标记删除，73.1 秒新 Pod 在另一工作节点运行。API 中为 `Running` 与 `Terminating` 各一个；旧容器仍为 `CONTAINER_RUNNING`，访问旧 Pod IP 与新 Pod IP 分别返回各自的主机名。恢复 kubelet 后 7.9 秒旧 Pod 从 API 删除，节点上没有残留的 web 容器，节点 `True KubeletReady`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)、[`evidence/cluster-version.txt`](evidence/cluster-version.txt) 与 [`evidence/kind-config.yaml`](evidence/kind-config.yaml)。kind v0.33.0，节点镜像 `kindest/node:v1.36.4` 固定 digest；Docker Desktop（Linux 虚拟机内核 7.0.12-linuxkit）。

## 三、执行步骤

见 `scripts/verify.sh` 与 `scripts/cni-conf.sh`。CNI 配置为 `ptp` + `host-local`，与 [`evidence/cni-conflist.json`](evidence/cni-conflist.json) 相同；跨节点路由用 `ip route add <对方 Pod 网段> via <对方节点 IP>` 手工添加。

调试中一次运行里新 Pod 在驱逐后 76 秒才运行，原因是另一工作节点首次拉取镜像；脚本在停止 kubelet 之前先在两个工作节点上拉取镜像，使重建时间只反映调度与启动。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 节点变为 `Unknown` 的时间取决于 `--node-monitor-grace-period`（默认 50 秒）、控制器检查周期（默认 5 秒）与最后一次心跳的时刻，三次运行分别为 47、48.4、51.5 秒；断言只检查 30—70 秒范围。
- 手工 CNI 只用于说明 CNI 的职责，不是可用于生产的网络方案；没有设置出网的地址转换，Pod 访问集群外部的情况没有验证。
- 只验证了停止 kubelet 这一种失联方式；节点断网、断电时容器也随之不可达，「旧实例仍在处理请求」只在 kubelet 停止而网络正常时成立。
- 官方文档中大面积失联时驱逐降速或停止的规则没有在本实验中验证。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

脚本退出时会恢复 kubelet 并删除集群；中途中断时可执行 `.cache/bin/kind-v0.33.0 delete cluster --name csl-nn`。

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
