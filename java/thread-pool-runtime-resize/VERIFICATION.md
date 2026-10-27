# 验证记录：运行时调整线程池参数

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| core 2 / max 4 扩到 8 / 16，先改 core | setCorePoolSize(8) 抛 IllegalArgumentException |
| 先改 max 再改 core | 8 / 16 |
| 8 / 16 缩到 2 / 4，先改 max | setMaximumPoolSize(4) 抛 IllegalArgumentException |
| 先改 core 再改 max | 2 / 4 |
| 4 个线程在执行、6 个排队时缩到 1 / 1 | 线程数仍为 4，10 个任务全部完成 |
| afterExecute 的 Throwable 参数 | execute 任务为异常本身，submit 任务为 null |
| 第一次 afterExecute 抛错 | 3 个任务由 2 个不同的工作线程执行 |
| CallerRunsPolicy 触发 | 任务在 main 线程执行，beforeExecute 少一次 |
| 按错误顺序应用下发的配置 | 未生效，池仍是 8 / 16 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 线程交错用闩锁与短暂等待固定；「立刻看线程数」是调整后 200ms 的观察，说明空闲回收发生在任务结束之后。
- 只测 JDK 自带的 ThreadPoolExecutor；Spring 的 ThreadPoolTaskExecutor 等包装类的 setter 最终委托给它，但可能在外层做了顺序处理，使用前需按版本核对。

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
