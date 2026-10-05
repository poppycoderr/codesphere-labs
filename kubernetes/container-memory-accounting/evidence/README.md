# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `accounting.tsv` | 上限 1 GB 的容器里，每一步之后进程与 cgroup 各项计数的变化量；`workingset.*` 两行是绝对值 |
| `under-limit.tsv` | 上限 300 MB：读 600 MB 文件、再申请 200 MB 匿名内存之后的计数，以及容器的退出码 |
| `over-limit.tsv` | 上限 300 MB：逐页写 400 MB 匿名内存的进度与容器的退出码、`OOMKilled` |
| `shm-over-limit.tsv` | 上限 300 MB：向 `/dev/shm` 写 400 MB 的进度与容器的退出码、`OOMKilled` |
| `jvm.tsv` | `-Xms1g` 与加上 `-XX:+AlwaysPreTouch` 时 JVM 启动后的 `VmRSS` 与 `memory.current` |
| `environment.txt` | 运行环境、镜像 digest、容器内核版本 |
