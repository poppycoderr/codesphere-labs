# 验证记录：告警规则在数什么

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 一次 +8 | increase(...[3s]) 最大值大于 8（外推） |
| 尖峰，有无 for | 没有 for 的 firing，for: 5s 的只到 pending |
| 持续错误 | pending 约 5 秒后 firing |
| keep_firing_for: 8s | 错误停止后多 firing 约 8 秒 |
| 序列消失 | 依赖它的告警恢复，absent 规则 firing |
| 分组 | 同组两条告警合成一条通知 |
| 静默 | Alertmanager 中为 suppressed |
| 接收端 500 | 重试，notifications_failed_total 增加，Prometheus 中仍 firing |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 间隔与窗口都是秒级，用来在短时间内复现时间语义；线上分钟级规则的外推误差更小。
- 状态每 0.25 秒采样一次，时长只精确到这个粒度，断言用范围。
- 通知失败的重试次数取决于 Alertmanager 的内部退避，只断言有请求、有失败计数。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
