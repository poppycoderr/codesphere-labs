# 容器的边界

对应文章：[container-boundaries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/container-boundaries.md)。

只用 Docker，全部在 `scripts/verify.sh` 里完成。只创建名字以 `csl-cb-` 开头的容器和卷，结束时删除：

1. 在 alpine、busybox、temurin（Ubuntu）三个镜像里读 `uname -r`；
2. 用一个 `--privileged --pid=host` 的临时容器，比较宿主机（Docker 的 Linux 虚拟机）PID 1 与容器进程的 7 种 namespace，并读容器进程在宿主机上的 uid；
3. `--memory 256m --cpus 0.5` 下读 cgroup v2 的 `memory.max`、`cpu.max`，对比 `nproc`、`/proc/meminfo` 与 JVM 看到的 CPU 数和最大堆，另测 1GB、2 CPU 与不限额；
4. 64MB 限额下不断申请内存，看退出码与 `OOMKilled`；
5. 在可写层和卷里各写一个文件，删除容器后再看；
6. `sleep`、`--init` 加 `sleep`、Java 程序分别作为 PID 1，测 `docker stop -t 5` 的耗时与退出码。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 1 分钟
make evidence
make clean
```
