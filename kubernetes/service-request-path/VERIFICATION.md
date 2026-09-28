# 验证记录：一次请求在集群里经过了什么

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **Service 与 kube-proxy**：kube-proxy 为 iptables 模式；EndpointSlice 中有 3 个就绪端点（分布在两个工作节点）；工作节点 nat 表中 `default/web` 有 3 条跳转到端点的规则，依次按概率 0.33、0.50、其余全部选择。
2. **按连接分配**：300 次请求，每次新建连接时分布为 106、104、90；复用一条长连接时 300 次全部落到 1 个 Pod。
3. **Service 配错**：选择器写错时端点数 0，请求 1ms 内连接被拒绝，工作节点 filter 表中有 `default/web-typo has no endpoints ... -j REJECT --reject-with icmp-port-unreachable`；`targetPort` 写错时端点数 3，请求同样 1ms 内连接被拒绝。
4. **DNS**：`resolv.conf` 为 `search default.svc.cluster.local svc.cluster.local cluster.local`、`options ndots:5`。`web` 解析到 ClusterIP，CoreDNS 收到 2 个查询（`web.default.svc.cluster.local.` 的 A、AAAA）；无头 Service 解析到 3 个 Pod IP；`example.com` 收到 8 个查询（三个 search 域的 A、AAAA 均为 NXDOMAIN，之后 `example.com.` 的 A、AAAA）；`example.com.` 只收到 2 个。
5. **NetworkPolicy**（kindnet 执行）：默认拒绝入站后请求超时（客户端超时 3 秒）；放行 `role=frontend` 后，无该标签的 Pod 仍超时，有标签的 Pod HTTP 200；给 frontend 只放行到 web:8080 的出站后，按名字解析 20025ms 后失败（`Temporary failure in name resolution`），按 ClusterIP 请求 HTTP 200；再放行到 CoreDNS 的 UDP/TCP 53 后按名字请求 HTTP 200。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)、[`evidence/cluster-version.txt`](evidence/cluster-version.txt)。kind v0.33.0，节点镜像固定 digest；kindnet 与 CoreDNS 使用 kind 节点镜像自带的版本。

## 三、执行步骤

见 `scripts/verify.sh`。调试中的两处修正：CoreDNS 有 2 个副本，最初把两份日志拼接后按一个行数截取，统计混入了之前的查询，改为按每个 Pod 各自的日志偏移截取；macOS 的 `tr` 会破坏多字节字符，拼接 `resolv.conf` 时改用 `paste` 与 `sed`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 请求分布是随机的，断言只检查新建连接分布到 3 个 Pod、长连接只落到 1 个 Pod。
- 外部域名的解析耗时取决于上游 DNS 与缓存，两次运行分别为 420ms 与 4ms，不作为结论；查询个数与顺序由 search 列表和 `ndots` 决定，是稳定的。
- DNS 被丢弃时的失败耗时取决于 glibc 的超时与重试设置和 search 域个数，本环境约 20 秒；断言只要求不少于 5 秒。
- 只验证了 kube-proxy 的 iptables 模式与 kindnet；IPVS、nftables 模式和其他 CNI 的规则形式不同，NetworkPolicy 的执行方式也不同。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

中途中断时可执行 `.cache/bin/kind-v0.33.0 delete cluster --name csl-sp`。

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
