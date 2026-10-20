# 离线 ext4 镜像的数据映射与时间副本

对应文章：[docs/system-design/filesystem-raid-boundaries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/filesystem-raid-boundaries.md)。

64MiB 普通镜像文件里的 51,200 字节文件分配为 13 个 4KiB 数据块，debugfs 显示 extent；导出后按字节一致。活动镜像删除后找不到路径，而先前独立复制的镜像仍能导出相同内容。

## 运行

```bash
make verify
make evidence
make clean
```

需要 Docker；首次拉取和依赖下载耗时不固定。脚本只使用本实验的文件和容器，不修改宿主机磁盘或外部服务。环境、证据与限制见 [VERIFICATION.md](VERIFICATION.md)。
