# 节点 NotReady

对应文章：[node-notready.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/node-notready.md)。

`scripts/verify.sh` 按 `config/kind.yaml` 创建名为 `csl-nn` 的 kind 集群（Kubernetes 1.36.4，1 个控制面 + 2 个工作节点，`disableDefaultCNI: true`），结束时删除。kind 与 kubectl 的获取方式见 `shared/scripts/lib.sh`。

1. 记录启动链：containerd 与 kubelet 的服务状态、静态 Pod 目录、kube-apiserver 镜像 Pod 的来源注解；
2. 没有 CNI 配置时：节点条件与污点、`kube-system` 各 Pod 状态、普通 Pod 与容忍 `not-ready` 污点的 Pod 分别卡在哪里、Pod 自动获得的默认容忍；
3. 用 `scripts/cni-conf.sh` 只给控制面节点写入 `ptp` + `host-local` 的 CNI 配置，再给工作节点写入；
4. 所有节点就绪后，从工作节点上的 Pod 直连控制面节点上的 CoreDNS 并解析域名；在各节点添加其他节点 Pod 网段的路由后再测一次；
5. 单副本 Deployment（`unreachable`、`not-ready` 容忍 20 秒）运行在工作节点上时停止该节点的 kubelet：记录节点变为 Unknown、旧 Pod 被标记删除、新 Pod 运行的时间，旧容器是否仍在运行并响应请求；最后恢复 kubelet。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 6 分钟
make evidence
make clean
```
