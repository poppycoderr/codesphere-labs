# 验证记录：提前结束也是迭代协议的一部分

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| for...of 中 break / 循环体抛错 | 调用 return()，连接关闭 |
| 正常读完 | 不调用 return() |
| const [first] = cursor | 取一个后调用 return() |
| 手写游标没有 return，break | 连接没有关闭 |
| 生成器 try/finally | break 与 return() 时执行；手动 next 后丢弃不执行 |
| for await 中 break | 异步生成器 finally 执行；可读流 destroyed=true |
| 类数组 for...of | TypeError：not iterable；Array.from 可用 |
| 遍历数组时 push | 新元素也被访问 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖 Node 24.21.0 的 V8 与 node:stream 行为；迭代协议本身由 ECMAScript 规范定义，浏览器一致。
- 「连接」是模拟的日志，不是真实的资源。

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
