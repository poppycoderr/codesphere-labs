# 验证记录：镜像 tag 与摘要

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 步骤 | 节点 | 镜像引用 | 策略 | 运行的内容 | kubelet 事件 |
|---|---|---|---|---|---|
| 首次部署 | worker | `app:1.0` | IfNotPresent | build-A（摘要 A） | 拉取 |
| 同一个 tag 重新推送后 | worker2 | `app:1.0` | IfNotPresent | build-B（摘要 B） | 拉取 |
| 同一时刻 | worker（原 Pod） | `app:1.0` | — | build-A（摘要 A） | — |
| 删除后重建 | worker | `app:1.0` | IfNotPresent | build-A（摘要 A） | 镜像已在节点上 |
| 显式 Always | worker | `app:1.0` | Always | build-B（摘要 B） | 拉取 |
| 之后再用默认策略 | worker | `app:1.0` | IfNotPresent | build-B（摘要 B） | 镜像已在节点上 |
| 按摘要 | worker、worker2 | `app@<摘要 A>` | IfNotPresent | 都是 build-A（摘要 A） | 已在节点上、拉取 |
| `latest` | worker2 | `app:latest` | Always（默认） | build-B | 拉取 |
| 仓库停止，Always | worker | `app:1.0` | Always | 容器状态 ErrImagePull | — |
| 仓库停止，默认策略 | worker | `app:1.0` | IfNotPresent | build-B，正常启动 | 镜像已在节点上 |

同一份 Dockerfile 与参数不用缓存构建两次，镜像 ID 不同。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。Kubernetes 1.37.0（kind v0.33.0），containerd 2.3.4，镜像仓库 registry 3。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 仓库是本地的 `registry` 容器，允许覆盖 tag；很多托管仓库可以把 tag 设为不可变，那时第二次推送会被拒绝。
- 用的是直接指定 `nodeName` 的裸 Pod，为的是控制 Pod 落在哪个节点；Deployment 滚动更新时结果取决于新 Pod 被调度到哪些节点，机制相同。
- 两次构建得到不同镜像 ID 的原因（层里文件的时间戳等）没有逐项分析；设置可重现构建参数后结果可能相同，没有在本实验中验证。
- 没有覆盖镜像签名与准入校验、`imagePullSecrets`、镜像垃圾回收，以及 kubelet 对已缓存镜像的凭据校验。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-09 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
