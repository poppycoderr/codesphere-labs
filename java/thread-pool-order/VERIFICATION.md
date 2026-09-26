# 验证记录：线程池接收任务的顺序

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

`core=2`、`max=4`、队列容量 2 时，7 个任务的去向：

- 任务 1、2 各新建一个核心线程；
- 任务 3、4 进入队列，**没有**新建线程；
- 队列满后，任务 5、6 才把线程扩到 4 个；
- 任务 7 被 `AbortPolicy` 拒绝。

推论：队列无界时 `maximumPoolSize` 不起作用。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。JDK 21.0.5。

## 三、执行步骤

`java src/PoolOrder.java`，输出与 `expected.txt` 用 `diff` 逐行比较。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.txt` | 程序输出 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 所有任务都卡在 latch 上，线程不会提前空闲，所以输出是确定的。真实负载下任务会完成、线程会空闲，线程数的变化取决于到达和完成的时序。
- 只验证接收顺序，不验证任何参数取值是否合理。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，输出与文章一致 | 补「配套实验」链接 |
