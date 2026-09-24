# 验证记录：包装类模式

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 10 条短信（2 条无效号码、3 条第一次被限流）：全部翻译成 `SmsFailed` 并对任何异常重试时，无效号码也被重试，供应商调用 17 次，调用方只看到「短信发送失败」；区分 `RetryableFailure` 与 `PermanentFailure` 时调用 13 次，调用方看到 `INVALID_NUMBER`。
2. `retry(cache(metrics(下游)))`：计量记录 10 次、错误 5 次，缓存命中 5 次不计入；`metrics(cache(retry(下游)))`：计量记录 10 次、错误 0。两种顺序的下游调用都是 10 次。
3. 代理对象的 `sendAll` 内部通过 `this` 调用 `send`：发送 3 条只经过代理 1 次。
4. `InvocationHandler` 抛出 `IOException`：接口方法声明了它时调用方收到 `IOException`，没有声明时收到 `UndeclaredThrowableException`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21。

## 三、执行步骤

`javac` 编译后运行，每行输出一个结果，脚本逐行断言。

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.tsv` | 每个场景的结果 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 供应商是确定性的模拟，只用来说明翻译与重试的组合效果。
- 计量与缓存是最小实现，不含时间窗口与过期。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
