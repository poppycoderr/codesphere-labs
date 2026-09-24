# Redis 持久化与恢复

对应文章：[redis-persistence-and-recovery.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-persistence-and-recovery.md)。

一个容器里运行 Redis 8.10.1 与 [LazyFS](https://github.com/dsrhaslab/lazyfs)（固定 commit，由 `docker/Dockerfile` 在本地构建）。LazyFS 是一个 FUSE 文件系统，没有 fsync 的写入只留在它自己的缓存里，收到 `lazyfs::clear-cache` 时丢弃，用来模拟断电时 page cache 的丢失。

覆盖：

- 崩溃矩阵：无持久化、默认 RDB、AOF `no` / `everysec` / `always` 在进程崩溃（`SIGKILL`，普通文件系统）与断电（LazyFS）下丢失多少已确认写入；
- 普通磁盘上四种配置的写延迟与吞吐；
- multi-part AOF 在重写期间的文件切换；
- 100 万个 key 的 fork、写时复制与四种文件的加载耗时；
- AOF 尾部截断、命令头损坏、值内部的静默损坏与 `redis-check-aof --fix`；
- Redis 8.10 的 `BACKUP START / SEAL` 与 `preload-file` 恢复。

写入客户端 `src/Writer.java` 自带最小的 RESP 客户端，在与 Redis 共享网络的 JDK 21 容器中运行。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、/dev/fuse；首次构建镜像约 4 分钟，运行约 8 分钟
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器（保留构建的镜像）
```

容器需要 `/dev/fuse` 与 `SYS_ADMIN` 权限（LazyFS 挂载），不暴露宿主机端口。
