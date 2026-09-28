# 一次请求在集群里经过了什么

对应文章：[service-request-path.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/service-request-path.md)。

`scripts/verify.sh` 按 `config/kind.yaml` 创建名为 `csl-sp` 的 kind 集群（Kubernetes 1.36.4，1 个控制面 + 2 个工作节点，默认 CNI kindnet），结束时删除。开始时给 CoreDNS 打开 `log` 插件，用来统计每次解析实际发出的查询。

- `src/web.py`：返回 Pod 名称、支持 HTTP/1.1 长连接的服务，3 个副本；
- `src/client.py`：`spread` 比较每次新建连接与复用一条长连接时的请求分布，`probe` 请求一次并输出结果与耗时，`resolve` 用 glibc 的 `getaddrinfo` 解析。

步骤：

1. 记录 ClusterIP、kube-proxy 模式、EndpointSlice 中的端点，以及工作节点 iptables 里该 Service 的规则；
2. 300 次请求的分布：新建连接与长连接；
3. 选择器写错与 `targetPort` 写错的 Service：端点数、请求结果，以及没有端点时 kube-proxy 写入的规则；
4. Pod 的 `resolv.conf`；解析 `web`、无头 Service、`example.com` 与 `example.com.` 时 CoreDNS 收到的查询；
5. 默认拒绝入站、按标签放行入站、只放行到 web 的出站、再放行 DNS。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 6 分钟
make evidence
make clean
```
