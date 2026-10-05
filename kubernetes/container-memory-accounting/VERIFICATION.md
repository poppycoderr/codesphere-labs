# 验证记录：容器内存记账

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

Docker 29.8.0，容器内核 7.0.12-linuxkit，cgroup v2，页大小 4096，透明大页 `always`。容器都用 `--memory` 与相同的 `--memory-swap` 启动（不允许换出）。

1. 映射 256 MB 匿名内存：`VmSize` +256 MB，`VmRSS`、cgroup `anon`、缺页次数都不变。逐页读：65536 次次缺页，`VmRSS` 与 `anon` 仍不变。逐页写：又 65536 次次缺页，`VmRSS` 与 `anon` 各 +256 MB。再写一遍：0 次缺页。
2. 打开透明大页后逐页写 256 MB：128 次次缺页。
3. 写 200 MB 文件：cgroup `file` +200 MB，进程 `VmRSS` 不变。丢弃页缓存后 `file` -200 MB。`read` 冷读后 `file` +200 MB，`VmRSS` 不变。
4. `mmap` 该文件后顺序访问每一页：冷缓存时 `RssFile` +200 MB、主缺页 1 次、次缺页 503 次；缓存已在内存时主缺页 0 次。关闭预读（`MADV_RANDOM`）后在冷缓存上访问 800 页：主缺页 800 次。
5. `memory.current - inactive_file`：`mmap` 访问两遍之后是 205 MB（200 MB 文件页全部在 `active_file`）；`read` 读两遍之后是 6 MB（200 MB 全部在 `inactive_file`）。
6. 向 `/dev/shm` 写 200 MB：cgroup `file` 与 `shmem` 各 +200 MB；对它调用 `posix_fadvise(DONTNEED)` 没有变化；删除文件后各 -200 MB。
7. 上限 300 MB：读完 600 MB 文件后 `memory.current` 291 MB、`file` 286 MB、`oom_kill` 0；随后申请并写 200 MB 匿名内存成功，`file` 降到 92 MB、`anon` 204 MB，容器正常退出。
8. 上限 300 MB：逐页写 400 MB 匿名内存，写过 200 MB 之后容器退出码 137、`OOMKilled=true`。向 `/dev/shm` 写 400 MB，写过 200 MB 之后同样退出码 137、`OOMKilled=true`，此时进程 `VmRSS` 只有 10 MB。
9. `-Xms1g -Xmx1g`：`Runtime.totalMemory` 1024 MB，`VmRSS` 150 MB；加上 `-XX:+AlwaysPreTouch`：`VmRSS` 1170 MB。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh`、`src/memlab.py` 与 `src/Rss.java`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 运行在 Docker Desktop 的 Linux 虚拟机里。缺页次数、预读窗口、文件页进入 `active_file` 的条件都由内核版本与回收算法决定，换内核后具体数字会变；脚本对这些项只断言范围。
- `kubectl top` 与 kubelet 驱逐使用的工作集是 `memory.current - inactive_file`（cgroup v2），这一点取自 Kubernetes 文档；本实验在容器里读同样的 cgroup 文件算出这个值，没有启动 kubelet。
- 透明大页的 128 次缺页对应 2 MB 的大页；宿主机关闭透明大页或内存碎片化时结果不同。
- 建立实验时，在 300 MB 上限的容器里向新文件写 600 MB 并 `fsync`，有过几次容器被 OOM 杀死（当时内核日志显示文件页约等于上限且几乎全部处于回写中，匿名内存只有约 5 MB）。之后单独重复 10 次没有再出现，无法稳定复现，因此没有归档，文章中也不把它当作实测结论。
- 没有覆盖换出、内核内存（slab、页表、套接字缓冲区）的大额占用、`memory.high` 限速与 PSI。
- JVM 的 150 MB 包含以源码方式启动时编译器占用的内存，不代表一个已编译应用的启动基线。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

脚本结束时会删除自己创建的容器 `labs-cma-run` 与数据卷 `labs-cma-data`。

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-05 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
