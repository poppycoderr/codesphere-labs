# LSM 树的放大

对应文章：[lsm-write-amplification.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/lsm-write-amplification.md)。

`src/LsmLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，用 RocksDB 11.1.2 的 Java 包，数据写在容器内的临时目录，容器退出即删除。关闭压缩，便于按字节对账；内存表 8 MB、第 1 层目标 32 MB，让 180 MB 的数据也能形成多层。

- 写放大：写入 150 万个键（键 16 字节、值 110 字节），分别用顺序键与随机键、分层压实与通用压实，从 RocksDB 的统计里读出刷盘与压实各写了多少字节。
- 空间放大：关闭自动压实，把同样的 30 万个键写 5 遍，比较压实前后的文件大小。
- 删除：把这 30 万个键全部删除，比较压实前后的文件大小。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 2 分钟，容器内临时占用约 1 GB
make evidence
make clean
```
