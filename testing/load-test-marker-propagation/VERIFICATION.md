# 验证记录：压测标记的传播

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.4，`--enable-preview`。

1. 压测请求在入口线程里直接写订单：写进影子表。
2. 把写订单交给线程池：工作线程看到「无标记」，订单写进正式表。`CompletableFuture.supplyAsync` 与 20 毫秒后的定时重试同样写进正式表。
3. `InheritableThreadLocal` 加单线程的池：压测请求先到时，工作线程由它创建，之后的正常订单也写进影子表；正常请求先到时，之后的压测订单写进正式表。
4. 消息：不带头的那条，消费者把订单写进正式表；带 `x-traffic` 头的那条写进影子表。
5. 缓存：压测与正常流量共用键时，正常请求读到压测写入的价格 1；压测流量的键加前缀后读到 9900。
6. 提交时捕获、执行后清理：压测订单写进影子表，随后的正常订单写进正式表，工作线程上没有残留标记。
7. `ScopedValue`：范围内写进影子表；范围内提交给普通线程池的任务看到「无标记」；`StructuredTaskScope` 分叉出的子任务看到 SHADOW；范围结束后同一线程看到「无标记」。
8. 写入处拒绝无标记的请求：标记在线程池里丢失时抛出 `IllegalStateException（traffic context missing）`，订单没有写入；入口明确标为 NORMAL 的请求正常写进正式表。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/MarkerLab.java`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 全部在一个进程里模拟：消息队列是一个 `BlockingQueue`，缓存是一个 `HashMap`，「正式表」与「影子表」是两个列表。实验说明的是标记在各类边界上的行为，不涉及真实的中间件、数据库与跨进程调用。
- 没有覆盖 HTTP 与 RPC 的头透传、消息重试与死信、批处理、数据库触发器与下游第三方系统。
- 没有使用任何现成的上下文传播库；这些库的做法与「提交时包装任务」相同，但覆盖范围各不相同，需要逐一确认。
- `StructuredTaskScope` 在 JDK 25 是预览特性，接口可能变化。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-05 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
