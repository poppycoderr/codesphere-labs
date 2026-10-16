# 验证记录：两阶段提交与 TCC：准备之后由谁保存决定

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 两个分支 PREPARE 后 COMMIT | 转账完成 |
| 准备之后两个连接都断开 | XA RECOVER 仍列出两个分支；同一行更新锁等待超时，另一行可以更新 |
| 只提交了分支 a 后协调者消失 | a 已生效，b 仍处于 PREPARED |
| MySQL 重启之后 | XA RECOVER 仍列出 3 个分支；同一行仍被锁住 |
| 恢复程序从新会话 XA COMMIT 剩余分支 | XA RECOVER 为空，两笔转账都完成 |
| TCC 朴素实现 | 空回滚后 balance=1100、frozen=-100；重复确认后 frozen=-100 |
| 只处理空回滚、不留标记 | 空回滚正确；迟到的 Try 冻结 100，永远不会释放 |
| 带分支状态表 | 三个场景都正确 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 两个分支在同一个 MySQL 实例的两个库里，只为演示 XA 分支的状态；真实的分布式事务分支位于不同实例，协调者与网络故障的组合更多。
- 「协调者的决定」在实验里由恢复程序直接给出；真实系统需要协调者在第二阶段之前把决定持久化。
- 朴素实现在「Cancel 之后迟到的 Try」场景里结果恰好是 balance=1000、frozen=0，是因为空回滚先错误地加了 100，Try 又减去了 100，两个错误互相抵消。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-17 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
