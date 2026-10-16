# 验证记录：参数放在哪里

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 单例服务把当前用户放在字段里，alice 的请求等待期间 bob 的请求进来 | alice 收到 `hello bob` |
| 作为参数传入 | alice 收到 `hello alice` |
| `count(from, to)` 参数写反 | 编译通过，返回 0 条，没有错误 |
| 换成参数对象 `Period(from, to)` 后写反 | 构造时抛 `IllegalArgumentException` |
| 线程池的上一个任务设置了租户、没有清理 | 下一个任务读到 `tenant-a` |
| 改成在 `finally` 里 `remove` | 下一个任务读到 null |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 交错顺序是手工固定的，说明的是「这种顺序下的结果」，不代表真实负载下的发生频率。
- 没有覆盖框架提供的请求作用域对象与它们的代理行为；那是把「请求数据」交给容器管理的另一种做法。
- `ThreadLocal` 只演示了线程复用时的残留，没有覆盖异步传递（见 testing/load-test-marker-propagation）。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
