# 文件删除与打开句柄

对应文章：[deleted-open-files.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/deleted-open-files.md)。

`src/deleted.py` 在固定 digest 的 python 3.14 容器里运行，容器挂一个 200 MB 的 tmpfs 作为 `/data`。一个子进程一直开着日志文件并按指令写入，主进程在另一边删除、清空、改名这个文件，每一步之后记录两个数：文件系统已用空间（`statvfs`，即 `df` 的口径）与目录下全部文件的占用（遍历累加 `st_blocks`，即 `du` 的口径）。

1. 删除仍被打开的文件、进程继续写、通过 `/proc/<pid>/fd/<n>` 读回内容、对该入口做截断、进程退出；
2. 清空仍被打开的文件之后继续写：写入方以 `O_APPEND` 打开与不加 `O_APPEND`；
3. 改名之后继续写；
4. 两个名字指向同一个文件时删掉其中一个。

每一步都是同步的写入与系统调用，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒，临时占用最多 150 MB 内存（tmpfs）
make evidence
make clean
```
