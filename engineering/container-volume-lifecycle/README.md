# 容器重建之后，数据和初始化还在吗

对应文章：[container-volume-lifecycle.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/container-volume-lifecycle.md)。

`scripts/verify.sh` 直接用 docker 命令依次验证：不指定卷时 MySQL 镜像的匿名卷；命名卷上删除并重建容器；在已有数据的卷上改密码并带同一份初始化脚本重启；宿主机目录与新命名卷挂到镜像已有内容的目录上；tmpfs 与可写层在重启后的差别。创建的容器与卷都以 csl-cvl- 开头，结束时只删除这些，以及本次产生的两个匿名卷。

```bash
make verify     # 需要 Docker；约 2 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
