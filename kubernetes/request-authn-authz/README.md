# 集群里的一次请求：认证、授权、准入

对应文章：[request-authn-authz.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/request-authn-authz.md)。

`scripts/verify.sh` 创建 kind 集群（Kubernetes 1.36.4），应用 `manifests/lab.yaml`（命名空间 lab、只读 ServiceAccount reader 与只允许 get/list pods 的 Role、一个值为 example_password 的合成 Secret、一个使用 reader 的 pause Pod），在控制面节点内用 curl 与 kubectl 依次检查：匿名请求、去掉 -k 的 TLS 校验、reader 令牌与别的受众的令牌、`kubectl create token` 与自动挂载的投射令牌的声明、两种凭据启动的 `kubectl proxy`、`kubectl auth can-i`、Secret 的 base64 与 etcd 中的存储值、restricted 命名空间里的特权 Pod。只读操作与合成的身份，不涉及真实凭据。

```bash
make verify     # 需要 Docker；约 2 分钟（不含首次拉取 kind 节点镜像）
make evidence   # 重新采集 evidence/
make clean
```
