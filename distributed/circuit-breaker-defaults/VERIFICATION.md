# 验证记录：熔断器的默认配置

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

默认配置（`CircuitBreakerConfig.ofDefaults()`）：基于次数的滑动窗口，大小 100，最少调用数 100；失败率阈值 50%；慢调用阈值 60 秒，慢调用比例阈值 100%；打开后等待 60 秒，不自动转半开；半开时放行 10 次，半开状态停留不限时。

| 场景 | 结果 |
|---|---|
| 默认配置，连续失败 99 次 | CLOSED，失败率 -1（未计算） |
| 第 100 次失败 | OPEN |
| 打开期间调用 50 次 | 下游被调用 0 次，50 次 `CallNotPermittedException` |
| 先成功 100 次，随后连续失败 49 次 | CLOSED，失败率 49% |
| 第 50 次连续失败 | OPEN |
| 等待时间过后、没有新调用 | 状态仍是 OPEN |
| 此时同时来 15 个调用 | 放行 10 个，拒绝 5 个，状态 HALF_OPEN |
| 这 10 个调用一直不返回，再过 400 ms | 仍是 HALF_OPEN，新调用被拒绝 |
| 10 个试探调用里 5 个失败 | OPEN |
| 10 个试探调用里 4 个失败 | CLOSED |
| 默认配置，100 次里 60 次业务异常 | OPEN |
| `ignoreExceptions(BusinessException)` | CLOSED，窗口内只有 40 次 |
| `recordExceptions(IOException)` | CLOSED，窗口内 100 次、失败 0 |
| 默认配置，100 次调用每次 30 秒才成功 | CLOSED，慢调用 0 |
| 慢调用阈值 2 秒、比例阈值 50% | OPEN，慢调用 100 |
| 导出全部失败、查询全部成功，各 50 次，共用一个熔断器 | OPEN，查询的下一次调用被拒绝 |
| 各用各的熔断器 | 导出 OPEN，查询 CLOSED |
| 按服务名建的熔断器，10 个实例里 1 个全部失败 | CLOSED，失败率 10% |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证了 Resilience4j 2.4.0 的 `CircuitBreaker` 核心模块，以编程方式配置。Spring Boot starter、Spring Cloud CircuitBreaker 可能带有自己的默认值，以实际生效的配置为准。
- 其他熔断器实现（Sentinel、Envoy 的异常点检测、服务网格）的默认值与状态机不同，本实验的数字不能套用。
- 调用耗时是上报的数值。真实调用里「30 秒才返回」通常先被客户端超时打断并记为失败，那是超时配置的作用，不是熔断器的。
- 没有覆盖基于时间的滑动窗口、与重试和限流组合时的装饰顺序。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-10 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
