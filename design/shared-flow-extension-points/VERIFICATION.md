# 验证记录：多个产品共用一套流程：骨架、扩展点与契约测试

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 共用流程里按产品判断 | 5 处分支；新增产品要逐一判断 |
| 流水线允许替换任一步骤，秒杀产品替换第 2 步 | 轨迹 [validate, fast-approve, pay]，riskChecked=false |
| 平台骨架下单课程 | 轨迹 [prepare, risk, pay, fulfil]，riskChecked=true |
| 新增直播 | 注册一个扩展，平台代码不变 |
| 未注册的产品下单 | 被拒绝，而不是落到默认分支 |
| 对 5 个扩展跑手续费契约 | 1 项失败：团购的退款单（-50）收了正的手续费 3 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 「按产品分支」只统计了一段示例代码里的分支数，没有执行；新增产品时漏改的后果是按代码推断的。
- 扩展点用 Java 接口表达，没有涉及跨进程的扩展（Webhook、规则引擎）与扩展的版本兼容。

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
