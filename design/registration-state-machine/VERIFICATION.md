# 验证记录：报名状态机

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 25 个组合中 7 个合法；散落分支与转换表有 6 处不一致，例如签到后还能取消、取消后还能被候补转正。
2. 所有状态都可从 PENDING 到达，没有出口的只有 CANCELLED 与 CHECKED_IN。
3. 不带版本号时，1000 轮中两个命令都「成功」的轮次接近全部（本次 1000 轮）；带版本号时 0 轮，另一个命令被拒绝 1000 次。
4. 状态先改为 CONFIRMED、通知失败后，重试 CONFIRM 被状态机判为非法，通知送达 0 封；写入待发送记录后，投递器第 3 轮送达 1 封。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21。

## 三、执行步骤

`javac` 编译后运行；转换矩阵输出到 `evidence/transition-matrix.tsv`，其余结果逐行断言。

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.tsv` | 每个场景的结果 |
| `evidence/transition-matrix.tsv` | 状态 × 命令的转换矩阵 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 并发场景的存储往返用 0.2ms 睡眠模拟；不带版本号时「两个都成功」的轮数取决于调度，只断言绝大多数轮次。
- 待发送记录与状态变化应在同一个数据库事务中写入，本实验用内存列表说明语义。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
