# 验证记录：等待人工澄清的代理

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 模型自问自答 | 2 条验收条件，都依据模型自己编的答案 |
| 新任务 | 停在 awaiting_input(q-late) |
| 重启后恢复 | 仍在等 q-late，提问次数仍是 1，没有重新提问 |
| 回答不是当前问题 | 拒绝 |
| 回答 q-late | 接受，转为等 q-group |
| 同一回答重复投递 | 忽略 |
| 回答 q-group | 接受，任务完成，草案只含人给出的答案 |
| 完成后再收到回答 | 拒绝 |
| 限时 10 秒，第 11 秒收到回答 | 拒绝，任务转为 expired |
| 等待中取消后再投递 | 拒绝 |
| 只允许问 1 个问题 | 回答后转为 needs_human_review，不再追问 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 模型是确定性的假实现，只用来驱动状态机；真实模型何时提问、问得是否充分，需要另行评估。
- 只覆盖单进程顺序处理与 SQLite 的 `begin immediate`；多个工作进程同时处理同一任务时还需要租约或乐观版本。
- 通知用户与收集回答的渠道（邮件、工单、聊天）没有实现。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-20 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
