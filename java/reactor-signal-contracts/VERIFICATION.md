# 验证记录：响应式流的信号契约

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 完成后再发 3 和错误 | 订阅者收到 10、20、complete；3 与错误交给 onNextDropped/onErrorDropped 钩子 |
| Flux.create 完成后再 next | 被忽略 |
| generate + take(3) | 上游只生成 3 个，收到 cancel |
| map 在第 3 个抛异常 | map 执行 3 次，上游收到 cancel |
| request(2)、request(3) | 上游收到 [2, 3]，订阅者收到 5 个 |
| publishOn 后 request(1) | 向上游请求 256，上游已发出 256 个 |
| limitRate(10) | 请求 [10, 8, 8, 8, 8, 8] |
| 延迟 3、2、1 秒 | flatMap [3, 2, 1]，concatMap [1, 2, 3] |
| 6 个 1 秒内层 | flatMap(f, 2) 最多 2 个、3 秒；flatMap(f, 6) 6 个、1 秒；concatMap 1 个、6 秒；doFinally 计数多 1 |
| Mono.just(f()) | 组装时执行 1 次，两次订阅仍 1 次 |
| Mono.defer | 订阅前 0 次，两次订阅 2 次 |
| 未订阅的 doOnNext | 0 次 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 时间相关的场景用虚拟时间，结论是算子的调度语义，不代表真实耗时。
- 预取数 256 是 Reactor 3.8.8 的默认值（reactor.bufferSize.small），可以通过系统属性或算子参数修改。
- doFinally 多算 1 是因为它在终止信号传到下游之后才执行，而 flatMap/concatMap 在处理内层的完成信号时就订阅了下一个内层；doOnTerminate 在信号传给下游之前执行。

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
