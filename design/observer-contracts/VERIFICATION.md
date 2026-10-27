# 验证记录：观察者的契约

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 同一函数订阅两次 | 朴素与 EventEmitter 调用 2 次，去重版 1 次 |
| 通知中退订自己 | 朴素发布器跳过下一个监听器；快照通知与 EventEmitter 不跳过 |
| once 回调里再次触发 | 先调用后摘除执行 5 次，先摘除 1 次，EventEmitter 1 次 |
| 第二个监听器抛错 | 朴素与 EventEmitter 停止通知；隔离版执行全部并抛 AggregateError；EventTarget 执行全部、以未捕获异常报告 |
| 同步通知中改值并再次通知 | 后面的监听器收到 [3,2]，停在旧值 2 |
| 普通对象做事件表、策略表 | emit('toString') TypeError；查 'constructor' 命中 Object；Map 走未知分支 |
| EventEmitter 无 error 监听器 | emit('error') 抛出 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖 Node 24.21.0 的 EventEmitter 与 EventTarget；浏览器的 EventTarget 把监听器异常交给 reportError，表现类似但报告渠道不同。
- 朴素发布器是为暴露问题写的典型写法，不代表某个具体库。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
