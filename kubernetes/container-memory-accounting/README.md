# 容器内存记账

对应文章：[container-memory-accounting.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/container-memory-accounting.md)。

`src/memlab.py` 在带内存上限、不允许换出的一次性容器里运行（cgroup v2），每一步之后读取进程的 `/proc/self/status`、`/proc/self/stat` 与容器的 `memory.stat`，输出变化量：

1. 匿名内存：映射 256 MB、逐页读、逐页写、再写一遍、解除映射；打开透明大页后逐页写；
2. 文件：写 200 MB、丢弃页缓存、`read` 读两遍、`mmap` 后顺序访问（冷、热）、关闭预读后访问 800 页；
3. `/dev/shm`（tmpfs）：写 200 MB、尝试丢弃缓存、删除；
4. 上限 300 MB：读 600 MB 文件后再申请 200 MB 匿名内存；写 400 MB 匿名内存；向 tmpfs 写 400 MB。

`src/Rss.java` 在 2 GB 上限的容器里对比 `-Xms1g` 与 `-Xms1g -XX:+AlwaysPreTouch` 启动后的常驻内存。

实验只在自己创建的容器和数据卷里读写，结束时删除数据卷。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟，临时占用约 800 MB 磁盘
make evidence
make clean
```
