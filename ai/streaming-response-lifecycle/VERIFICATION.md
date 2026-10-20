# 验证记录：流式响应的生命周期

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 一段 `data:` 后少了空行 | 与下一段合成一个事件 |
| 多行 `data:` | 用换行拼成一个事件 |
| 异步生成器，客户端读 3 个后断开 | 服务端停在 3 个；收到 CancelledError，finally 执行 |
| 同步生成器（线程池），客户端读 3 个后断开 | 服务端多生成 1 个，停在 4 个；finally 没有执行 |
| 生成到一半抛异常 | 状态码 200，客户端收到 2 个 token 后 `RemoteProtocolError` |
| 失败写成 `event: error` | 状态码 200，客户端收到 2 个 token 和一个错误事件 |

同步生成器的结果在 5 次运行中一致。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 Starlette（FastAPI 0.156.2）与 uvicorn 0.54.0 的组合；其他 ASGI 服务器检测断开的时机不同。
- 同步生成器「被挂起而没有关闭」意味着它持有的资源（推理请求、文件、连接）不会在 finally 里释放；实际资源占用与回收时机没有测量。
- 没有覆盖反向代理的缓冲、浏览器 `EventSource` 的重连与 `Last-Event-ID`。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-21 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
