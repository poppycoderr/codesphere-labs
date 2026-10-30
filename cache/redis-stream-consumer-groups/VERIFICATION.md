# 验证记录：Redis Stream 消费组：投递、处理、确认

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 读到不 XACK | 待确认 3 条，持有者 c1 |
| c1 消失，c2 用 '>' | 拿到 0 条 |
| c1 用 '0' 重读 | 拿回同样 3 条 |
| XAUTOCLAIM 接管 | 持有者变为 c2，投递次数 3 |
| 接管后 c1 的 XACK | 返回 1；c2 再确认返回 0 |
| 毒消息 | 投递 3 次后转死信并确认 |
| 裁剪未确认的消息 | 近似裁剪删 0 条；精确裁剪删 3 条，XAUTOCLAIM 报告 3 条已删除 |
| XACK | 待确认清零，XLEN 不变 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 单实例 Redis；主从切换时未复制的消息与确认可能丢失，没有覆盖。
- 「消费者消失」用不再调用来模拟，没有真实的进程崩溃；处理逻辑只记录是否确认，没有真实副作用。

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
