# Running 不等于可用

对应文章：[pod-readiness-rollout.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/pod-readiness-rollout.md)。

`scripts/verify.sh` 创建名为 `csl-pr` 的 kind 集群（Kubernetes 1.36.4，单节点），结束时删除。kind 二进制由 `shared/scripts/lib.sh` 按平台从官方 release 下载并校验 sha256；kubectl 使用控制面节点里自带的版本，不需要在本机安装。

- `src/app.py`：被发布的服务。`START_DELAY` 模拟预热（期间不监听端口），`REQUIRE_DB_URL` 模拟新版本依赖的新配置，`DEP` 为下游地址；`/healthz` 只反映进程自身，`/ready` 检查配置，`/ready-with-dep` 额外检查下游。收到 SIGTERM 立即退出。
- `src/client.py`：集群内的压测客户端，每 20ms 通过 Service 发一个新连接请求，结束时输出成功数、按原因归类的失败数和各版本的响应数。
- `scripts/manifest.py`：生成 Deployment、Service 和客户端 Pod。

场景：

1. 预热 10 秒的版本，没有就绪探针，滚动发布；
2. 同上，加就绪探针与 `preStop` 3 秒；
3. 有就绪探针、去掉 `preStop`；
4. 新版本缺配置、永远不就绪：`maxSurge=1, maxUnavailable=0, progressDeadlineSeconds=20`，随后 `rollout undo`；再用 `maxSurge=0, maxUnavailable=1` 看卡住时的端点数；
5. 3 个副本共用一个下游，下游停止 30 秒再恢复：探针检查依赖 vs 探针只检查自身。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 15 分钟（首次需拉取节点镜像）
make evidence
make clean
```
