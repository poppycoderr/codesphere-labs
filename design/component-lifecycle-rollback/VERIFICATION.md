# 验证记录：组件的启动顺序、部分失败与逆序关闭

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 依赖图拓扑排序 | config → db → cache → orders → http |
| 删掉 db | 启动前报「缺少依赖：orders → db」，没有组件被启动 |
| config 反过来依赖 http | 启动前报出环 http → orders → db → config → http |
| orders 启动失败，朴素启动 | cache、db、config 仍在运行 |
| orders 启动失败，逆序关闭已启动的 | 依次关闭 cache、db、config，无组件运行，抛出原始异常 |
| 关闭时 cache 抛错，朴素循环 | db、config 未关闭 |
| 继续关闭其余组件并汇总 | 全部关闭，汇总异常包含 1 个失败 |
| 路由已注册而缓存未预热 | 按依赖就绪判断为未就绪 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 组件与故障都是模拟的，启动与关闭是同步调用，没有超时；真实框架还要处理关闭超时、并行启动与异步就绪。
- Spring 的 SmartLifecycle 阶段、优雅停机等具体行为见站内 Spring 生命周期文章。

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
