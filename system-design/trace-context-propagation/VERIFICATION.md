# 验证记录：追踪上下文在哪一跳丢失

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 子 span / 服务端 span |
|---|---|
| 同一线程 | 同一条 trace |
| 普通线程池 | 新 trace 的根 |
| `Context.taskWrapping` 包装的线程池 | 同一条 trace |
| `CompletableFuture.runAsync`（公共池） | 新 trace 的根 |
| `runAsync` 传入包装后的线程池 | 同一条 trace |
| 虚拟线程 | 新 trace 的根 |
| 提交前 `Context.current().wrap(task)` | 同一条 trace |
| 手写 HttpClient，不注入请求头 | 服务端收到 `traceparent=null`，另一条 trace |
| 注入 `traceparent` | 标志位 `03`，同一条 trace |
| 根 span 不采样 | 仍传播 `traceparent`，标志位 `02`；客户端与服务端都导出 0 个 span |
| 开始但没有 `end()` 的 span | 导出 0 个 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 OpenTelemetry Java SDK 的手工埋点；Java agent 的自动埋点会为常见线程池和 HTTP 客户端自动包装与注入，结论只适用于没有被自动埋点覆盖的代码。
- 标志位 `02` 是 W3C Trace Context Level 2 的「随机 trace-id」位，`01` 是采样位。
- 服务端与客户端在同一进程，没有覆盖跨进程的时钟偏差与导出失败。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-20 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
