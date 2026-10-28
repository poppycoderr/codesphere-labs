# 声明式 API 背后是控制循环

对应文章：[control-loop.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/control-loop.md)。

`scripts/verify.sh` 创建 kind 集群（Kubernetes 1.36.4），部署 3 副本的 pause 容器（`manifests/web.yaml`，镜像已预置在节点里），依次：手工把 ReplicaSet 副本数改为 1；删除一个 Pod；把 kube-controller-manager 的静态 Pod 清单移出 `/etc/kubernetes/manifests` 使其停止，然后修改 Deployment 模板并删除一个 Pod，10 秒后读取 generation、observedGeneration、Pod 数、ReplicaSet 数与 conditions；最后把清单移回，等待 rollout 完成。

```bash
make verify     # 需要 Docker；约 3 分钟（不含首次拉取 kind 节点镜像）
make evidence   # 重新采集 evidence/
make clean
```
