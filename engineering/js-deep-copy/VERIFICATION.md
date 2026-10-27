# 验证记录：深拷贝不是一个函数

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| structuredClone | 保留 Date、Map、NaN、undefined、环与共享引用；访问器变数据属性、Symbol 键丢失、函数抛 DataCloneError、类实例变普通对象 |
| JSON 往返 | 丢 undefined、Date 变字符串、Map 变 {}、NaN 变 null、共享引用变两份；环与 BigInt 抛 TypeError |
| 手写递归 | 环导致栈溢出；Date、Map 变 {}；共享引用变两份；hasOwnProperty 字段报错；__proto__ 键改掉副本原型 |
| 展开 Money 实例 | 私有字段丢失 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- structuredClone 的行为由 HTML 规范的结构化克隆算法定义，Node 24.21.0 与浏览器一致；Symbol 键与访问器的处理以本实验观察为准。
- 手写递归是常见写法的代表，不代表某个具体库（lodash 的 cloneDeep 处理了其中多数情况）。

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
