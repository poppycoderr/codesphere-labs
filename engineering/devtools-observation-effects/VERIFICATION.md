# 验证记录：调试工具看到的不一定是当时的状态

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| console.log 后修改对象 | 折叠预览 total=3，展开 total=4 |
| JSON.stringify 快照 | 丢 undefined 与函数，NaN 变 null；BigInt、循环引用抛 TypeError |
| 条件 items.pop(), false | 不暂停，但两次 checkout 返回 2、1 |
| 断点上停 1 秒 | 超时判断走降级分支 |
| Replay XHR | 服务端收到 2 次下单 |
| 覆盖率 | 点击前 handleExport 0 次，点击后 1 次 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 用 CDP 直接驱动调试器，与在 DevTools 界面里手动操作是同一套协议，但没有截取界面。
- 控制台「预览是旧值、展开是新值」依赖 Chromium 的实现；其他浏览器的控制台行为没有测。
- 覆盖率只统计本次会话；代码是否真的无用，要结合所有入口和线上数据判断。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-30 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
