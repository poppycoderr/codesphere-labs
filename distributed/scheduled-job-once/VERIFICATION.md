# 验证记录：多实例定时任务只跑一次

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 两个实例都执行 | 结算表主键挡住重复，通知发了 2 次 |
| 同时抢租约 | 只有 A 拿到 |
| 停顿后被接管，不设防 | A 的旧结果覆盖 B |
| 带令牌与运行键 | A 的写入被拒绝，保留 B 的结果 |
| 同一周期再次完成 | 运行键拒绝 |
| cancel(true) | 两个任务都没停，照样写入 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 两个实例在同一进程里，用 sleep 模拟停顿，不是两个独立进程；交错因此是确定的。
- 令牌校验在写入事务里锁住租约行；结果写到别的存储（外部 API、文件）时，需要下游自己校验令牌。
- 阻塞在 JDBC 调用里不响应中断是本实验所用驱动（mysql-connector-java 8.0.27）的行为。

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
