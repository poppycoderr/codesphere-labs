# 验证记录：Running 不等于可用

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

压测客户端每 20ms 通过 Service 发一个新连接请求。

1. **没有就绪探针**：预热 10 秒的新版本发布 3 秒后，3 个新 Pod 都是 `Running`、ready 为 true，日志还停在预热；`rollout status` 3.5 秒返回成功；2,119 次请求中 456 次连接被拒绝（约 9 秒）。
2. **就绪探针 + preStop 3 秒**：发布 3 秒后只有 1 个新 Pod，`Running` 但未就绪；发布用时 33.7 秒；2,856 次请求 0 次失败。
3. **有就绪探针、没有 preStop**：2,708 次请求中 4 次失败（3 次超时、1 次连接被拒绝）。只有一次运行，次数随时序变化，只说明存在这个窗口。
4. **坏版本**（`maxSurge=1, maxUnavailable=0, progressDeadlineSeconds=20`）：新 Pod `Running`、`0/1`，事件为 `Readiness probe failed: HTTP probe failed with statuscode: 503`；3 个旧 Pod 保留，端点 3 个，2,367 次请求 0 次失败且全部由旧版本处理；`rollout status` 以 `exceeded its progress deadline` 失败，`Progressing` 条件为 `False ProgressDeadlineExceeded`，没有自动回滚；`rollout undo` 后 3.5 秒新 Pod 删除完毕。`maxSurge=0, maxUnavailable=1` 时卡住期间只剩 2 个旧 Pod、2 个端点。
5. **共用依赖停止 30 秒**：
   - 探针检查依赖：端点 0，每个 Pod 重启 2 次；请求先是 20 次 503，之后是 39 次连接被拒绝、34 次超时；依赖恢复后 8.8 秒才回到 3 个端点，期间每个 Pod 又重启 1 次。
   - 探针只检查自身：端点保持 3 个，0 次重启；195 次请求全部快速返回 503；依赖恢复后 1.0 秒恢复。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/cluster-version.txt`](evidence/cluster-version.txt)。kind v0.33.0，节点镜像 `kindest/node:v1.36.4` 固定 digest，单节点；Docker Desktop（Linux 虚拟机内核 7.0.12-linuxkit）。

## 三、执行步骤

见 `scripts/verify.sh`。调试中的两处修正：

- kind 会把宿主机的代理环境变量写进节点，指向 `127.0.0.1` 的代理在节点里不可达，镜像拉取失败；`kind_up` 创建集群时不传代理变量。
- 第一版服务作为 1 号进程没有注册 SIGTERM 处理函数，信号被忽略，旧 Pod 每次等满 30 秒宽限期才被 SIGKILL，「去掉 preStop」的对照因此没有意义。现在服务收到 SIGTERM 立即退出，模拟常见应用。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 单节点集群，kube-proxy 与 Pod 在同一节点上，摘除端点的传播很快；多节点集群的窗口更长，第 3 点的失败次数不能外推。
- 失败次数与耗时随调度和探针周期有小幅波动；断言只检查有无失败、失败类型、端点数与相对快慢，不检查精确次数。
- 服务用 Python 模拟，预热、配置缺失和依赖检查都是人为设定；结论针对 Kubernetes 的行为，不针对具体框架。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

脚本退出时删除 kind 集群；中途中断时可执行 `.cache/bin/kind-v0.33.0 delete cluster --name csl-pr`。

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
