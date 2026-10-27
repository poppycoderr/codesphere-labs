# 验证记录：流程骨架里的上下文与失败语义

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 外层流程里调用了另一个流程，两者都 set/remove 同一个 ThreadLocal | 内层结束后外层读到 null |
| 进入时保存、退出时恢复；或把上下文作为参数传递 | 外层仍是 t1/A |
| 单线程池，第一个任务抛错，清理写在正常路径末尾 | 下一个任务读到 tenant-x/X1 |
| 清理放进 finally | 下一个任务读到 null |
| 流程里 supplyAsync 到线程池 | 任务里读 ThreadLocal 得到 null；捕获值则得到 t2/C |
| 两个线程同时先查后执行同一命令 | 执行 2 次；原子占位（putIfAbsent）执行 1 次 |
| 重试时每次生成随机事件 ID | 扣款 2 次；用调用方的命令键去重扣款 1 次 |
| 收尾只写在正常返回之后，执行抛错 | 状态停在「处理中」；finally 写终态得到「失败（下游超时）」 |
| Map<String,Object> 上下文的键拼错 | 读到 null，不报错 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- ConcurrentHashMap.putIfAbsent 代表「一次原子的占位」，在跨进程场景里对应数据库唯一约束或条件写入；本实验不涉及数据库。
- 线程交错用闩锁固定，只说明先查后执行可能执行两次，不测量概率。
- Spring 事务里「返回失败结果不会回滚」的规则见站内事务传播一文的实测，本实验不重复。

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
