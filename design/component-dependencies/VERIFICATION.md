# 验证记录：包之间的依赖：环、端口与 common 包

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 版本 | jdeps 包依赖 | 不稳定度 | ArchUnit 无环规则 |
|---|---|---|---|
| v1 互相引用 | billing → order，order → billing | 都是 0.50 | 违反：billing → order → billing |
| v2 订单定义端口 | billing → order | order 0.00，billing 1.00 | 通过 |
| v3 加入引用订单的 common | billing → order，common → order，order → common | order 0.33，common 0.50，billing 1.00 | 违反：common → order → common |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 不稳定度只按包之间的边数计算，没有区分依赖的强弱（继承、调用、只引用类型）。
- 代码库很小，只为展示依赖方向的变化；包的划分是否合理还要看变化原因，见文章。

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
