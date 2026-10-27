# 验证记录：React 的状态属于谁

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 同一次点击三次 setCount(count + 1) | 显示 1；handler 里读到的仍是 0 |
| 三次 setCount(c => c + 1) | 再加 3 |
| push 进原数组后 setCart({...cart}) | 外层显示 2 件，memo 子组件仍是 1 件 |
| 修改字段后 set 同一个对象 | 重新渲染 0 次 |
| 下标做 key，删掉第一行 | 第一行的草稿出现在下一行 |
| id 做 key | 草稿随行删除 |
| 以 userId 作为 key | 切换用户时草稿被重置 |
| value={qty || ''}，qty 为 0 | 显示为空；?? 显示 0 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 在 jsdom 中运行，不涉及真实浏览器的布局与绘制；验证的是 React 公开的状态与协调契约。
- React 的批处理与调度细节随版本变化，本实验只引用 React 19 文档说明的行为。

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
