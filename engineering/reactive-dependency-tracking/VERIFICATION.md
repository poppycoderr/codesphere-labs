# 验证记录：响应式系统的三本账

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 分支切换后修改不再读取的字段 | 不清理旧依赖额外重跑 2 次；清理与 Vue 0 次 |
| 外层在嵌套 effect 之后读取 count | 不用栈：外层不重跑；用栈与 Vue：重跑 1 次 |
| effect 里 n = n + 1 | 不排除自身：RangeError；Vue：n=1 |
| 同一轮改三个字段 | 同步 3 次；去重微任务队列 1 次 |
| 刷新队列时任务抛错且未复位 | 之后的任务不再执行 |
| getSnapshot 每次返回新对象 | Maximum update depth exceeded，并提示应缓存快照 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 最小实现只为暴露这几条规则，不是完整的响应式系统；Vue 的组件更新调度在 runtime-core 中，本实验只用 @vue/reactivity 的 effect 与 scheduler 选项。
- React 部分在 jsdom 中运行。

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
