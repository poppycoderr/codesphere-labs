# Compose 里的依赖就绪、网络与项目边界

对应文章：[compose-readiness-and-boundaries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/compose-readiness-and-boundaries.md)。

`src/` 里是五个最小的 Compose 文件（都只用 Alpine）：默认 depends_on、service_healthy、永远不健康的依赖、端口发布、项目卷与外部卷。`scripts/verify.sh` 用不同的项目名启动它们并观察结果；项目名都以 csl-crb- 开头，外部卷是 csl-crb-shared，结束时只删除这些。

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
