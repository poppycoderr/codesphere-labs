# 验证记录：容器的边界

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **内核是共享的**：alpine、busybox、temurin（Ubuntu）三个镜像里 `uname -r` 都是 `7.0.12-linuxkit`，即 Docker Desktop 虚拟机的内核。
2. **namespace**：pid、net、mnt、uts、ipc、cgroup 六种与宿主机不同；user namespace 与宿主机相同。容器进程在宿主机上的 uid 是 0，容器里的 root 就是宿主机的 root。容器里 `sleep` 是 PID 1。
3. **cgroup v2 限额与进程看到的资源**（`--memory 256m --cpus 0.5`）：
   - 限额写在 `memory.max=268435456`、`cpu.max=50000 100000`；
   - `nproc` 是 6，`/proc/meminfo` 的 MemTotal 是 15970MB——工具看到的是整台机器；
   - JVM 看到 1 个 CPU、最大堆 121MB（限额较小时按 `MinRAMPercentage` 取一半）；1GB、2 CPU 时是 2 个 CPU、247MB（按 `MaxRAMPercentage` 取四分之一）；不限额时是 6 个 CPU、3994MB。
4. **OOM**：64MB 限额下不断申请内存，退出码 137，`OOMKilled=true`。
5. **可写层与卷**：`docker diff` 显示 `/tmp/in-layer.txt` 写在可写层；删除容器后，新容器里没有这个文件，卷里的文件还在。
6. **PID 1 与 SIGTERM**（`docker stop -t 5`）：`sleep` 作为 PID 1 时没有处理 SIGTERM，等满 5.2 秒后被 SIGKILL，退出码 137；加 `--init` 由 tini 转发信号，0.1 秒退出，退出码 143；Java 程序作为 PID 1，JVM 处理了 SIGTERM，0.1 秒退出，退出码 143。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。Docker Desktop（Linux 虚拟机内核 7.0.12-linuxkit，cgroup v2），镜像固定 digest。

## 三、执行步骤

见 `scripts/verify.sh`。调试时发现这台机器上 `docker stop` 不带 `-t` 时约 3 秒就发 SIGKILL，与常见的 10 秒不同，所以脚本显式传 `-t 5`，不依赖守护进程的默认值。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 「宿主机」是 Docker Desktop 的 Linux 虚拟机，不是 macOS；在 Linux 服务器上直接运行 Docker 时结论相同，但内核版本不同。
- 只验证了 Docker 的默认配置；开启 user namespace 重映射（userns-remap）或 rootless 模式时，第 2 点的 uid 结论不同。
- Kubernetes 里 Pod 的终止宽限期默认 30 秒，由 `terminationGracePeriodSeconds` 控制；本实验只验证了信号本身的行为。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
