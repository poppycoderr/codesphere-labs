# 验证记录：文件删除与打开句柄

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 进程写了 100 MB 日志：文件系统已用 100 MB，目录下文件合计 100 MB。
2. `rm app.log` 之后：目录为空，目录下文件合计 0 MB，文件系统已用仍是 100 MB；写入进程的 fd 指向 `/data/app.log (deleted)`。
3. 进程继续写 50 MB：文件系统已用 150 MB，目录下文件合计仍是 0 MB。
4. 通过 `/proc/<pid>/fd/<n>` 仍能读到文件内容；对这个入口做截断后文件系统已用降到 0 MB，进程没有退出。
5. 写 100 MB、清空、再写 1 MB：写入方以 `O_APPEND` 打开时文件长度 1 MB、实际占用 1 MB；不加 `O_APPEND` 时文件长度 101 MB、实际占用 1 MB。
6. 写 10 MB、`mv app.log app.log.1`、再写 10 MB：`app.log.1` 长度 20 MB，`app.log` 不存在。
7. 20 MB 的文件有两个名字，删掉其中一个：文件系统已用仍是 20 MB。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。容器内核 7.0.12-linuxkit，文件系统是 tmpfs。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 文件系统是 tmpfs，空间来自内存；「名字删除后数据保留到最后一个打开者关闭」是 POSIX 语义，在 ext4、xfs、overlayfs 上相同，但没有在这些文件系统上逐一运行。
- 稀疏文件（长度 101 MB、占用 1 MB）在不支持空洞的文件系统上表现不同。
- 没有运行 logrotate 等具体工具，也没有验证容器运行时的日志轮转与 kubelet 对临时存储的统计方式；文章里这两部分只引用官方文档。
- 没有覆盖 NFS（删除打开的文件会留下 `.nfs` 开头的文件）。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-09 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
