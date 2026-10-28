# ByteBuf 的引用计数

对应文章：[netty-bytebuf-refcount.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/netty-bytebuf-refcount.md)。

`src/Lab.java`（单文件，依赖见 `deps.txt`）把泄漏检测设为 PARANOID，用 `EmbeddedChannel` 驱动各个处理器，记录每个场景结束时消息的 `refCnt`：吞掉消息并触发泄漏报告；只往下传的消息在真实通道（Local 传输）与 `EmbeddedChannel` 里的去向；`SimpleChannelInboundHandler` 转交消息前是否 `retain`；`slice`、`duplicate`、`retainedSlice`；拒绝分支直接 `close`；写入已关闭的通道；`ctx.write` 与 `channel().write` 经过的出站处理器。泄漏报告原文保存在 `leak-report.txt`。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
