# 镜像 tag 与摘要

对应文章：[image-tag-identity.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/image-tag-identity.md)。

`scripts/verify.sh` 用 kind 创建一个三节点集群（Kubernetes 1.37.0，1 个控制面、2 个工作节点），另起一个本地镜像仓库容器，按顺序执行：

1. 构建内容为 `build-A` 的镜像，推送为 `app:1.0`，在节点 worker 上启动 Pod；
2. 构建内容为 `build-B` 的镜像，用同一个 tag `app:1.0` 再推送一次；
3. 在节点 worker2 上按 `app:1.0` 启动 Pod；查看 worker 上一直在运行的 Pod；
4. 删除 worker 上的 Pod，按同一个 tag 重建；
5. 在 worker 上用 `imagePullPolicy: Always` 启动；之后再用默认策略启动一个；
6. 两个节点上都按 `app@<摘要 A>` 启动；
7. 按 `app:latest` 启动，查看默认的拉取策略；
8. 停掉仓库后，分别用 `Always` 与默认策略在 worker 上创建 Pod；
9. 同一份 Dockerfile 与参数，不用缓存构建两次，比较镜像 ID。

每个 Pod 记录运行的内容（镜像里的 `/version`）、生效的拉取策略、`status.containerStatuses[].imageID` 里的摘要与 kubelet 的拉取事件。摘要每次构建都不同，输出里用「摘要 A、摘要 B」代替，原始值存在 `digests.txt`；其余内容是确定的，脚本把它与预期逐行比较。集群与仓库在脚本结束时删除。

## 快速运行

```bash
make verify     # 需要 Docker；约 3 分钟，首次运行要下载 kind 与节点镜像
make evidence
make clean
```
