# 镜像层与被删除的文件

对应文章：[image-layers-and-deleted-files.md](https://github.com/poppycoderr/codesphere/blob/master/docs/kubernetes/image-layers-and-deleted-files.md)。

`scripts/verify.sh` 用同一个基础镜像（固定 digest 的 busybox）构建 5 个小镜像，区别只在 Dockerfile 的写法。构建时需要一个「令牌」文件（内容是写在脚本里的演示值）和一个 20 MB 的临时文件，最终镜像里都不应该留下它们：

1. `leaky`：`COPY` 令牌、使用、下一条 `RUN rm`；另一条 `RUN` 生成大文件、再下一条 `RUN rm`；
2. `same-run`：使用与删除写在同一条 `RUN` 里，大文件的生成与删除也在同一条 `RUN` 里；
3. `multi-stage`：在构建阶段使用，最终阶段只 `COPY --from` 需要的产物；
4. `secret-mount`：用 `RUN --mount=type=secret` 挂载令牌；
5. `arg`：用 `ARG` 与 `--build-arg` 传入令牌。

每个镜像记录：大小、容器里能否看到这两个文件、`docker save` 导出后每一层新增的文件与删除标记、哪些层的内容里能找到令牌、镜像配置与 `docker history` 里有没有令牌（`src/inspect_image.py` 只用标准库解析导出的包）。镜像在脚本结束时删除。

## 快速运行

```bash
make verify     # 需要 Docker 与 python3；约 1 分钟
make evidence
make clean
```
