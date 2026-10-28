# 验证记录：ByteBuf 的引用计数

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 入站处理器吞掉消息 | refCnt=1；回收后报告 1 条泄漏，最近访问记录指向 SwallowHandler，并带创建位置 |
| 只往下传，真实通道 | 管道尾部释放，refCnt=0 |
| 只往下传，EmbeddedChannel | refCnt=1，进入 readInbound 队列 |
| SimpleChannelInboundHandler 转交、不 retain | 返回后 refCnt=0，消费者读取抛 IllegalReferenceCountException |
| 转交前 retain | 返回后 refCnt=1，消费者用完释放为 0 |
| 释放 slice | 原缓冲区 refCnt=0 |
| retainedSlice | 原缓冲区 refCnt=2，各自释放后为 0 |
| 拒绝分支直接 close | refCnt=1 |
| 写入已关闭的通道 | future 失败，Netty 已释放，调用方再释放抛异常 |
| ctx.write / channel().write | 经过 [OutA] / [OutB, OutA] |
| 其余场景按约定释放 | 再次检测 0 条泄漏 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 泄漏检测依赖 GC 回收对象之后的下一次分配；实验主动调用 System.gc() 并循环分配，生产环境里报告的时间点不确定。
- Local 传输在同一 JVM 内传递同一个对象，没有经过 socket；尾部释放的行为与传输无关，由 DefaultChannelPipeline 决定。
- 默认分配器在 Netty 4.2 中是 AdaptiveByteBufAllocator；池化与非池化分配器的泄漏检测机制相同，没有分别测量。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
