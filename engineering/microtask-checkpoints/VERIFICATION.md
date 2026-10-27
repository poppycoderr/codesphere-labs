# 验证记录：微任务什么时候执行

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| ES 模块顶层调度 | 同步 → Promise.then → queueMicrotask → nextTick → setTimeout |
| CommonJS 主模块调度 | 同步 → nextTick → Promise.then → queueMicrotask → setTimeout |
| resolve(另一个 Promise) | 比 resolve(值) 晚两拍 |
| then 回调返回 Promise | 比返回值晚两拍 |
| 先 resolve 待定 Promise 再 reject | reject 无效，内层兑现后外层兑现 |
| await 期间 | 别的定时器先执行 |
| 一万个链式微任务 | 全部执行完 setTimeout 才执行 |
| Chromium 用户点击 | 监听器 1 → 它的微任务 → 监听器 2 |
| Chromium 脚本 click() | 监听器 1 → 监听器 2 → 微任务 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- setImmediate 与 setTimeout(0)、requestAnimationFrame 与 setTimeout 的先后取决于时机，不作断言。
- 「两拍」是对比链式 then 的相对顺序得出的，具体数字是 ECMAScript 规范与 V8 当前实现的结果，规范曾为 await 减少过额外的拍数。

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
