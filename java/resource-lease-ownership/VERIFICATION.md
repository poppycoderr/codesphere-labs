# 验证记录：许可、资源与租约

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.4。

1. 2 个许可都借出时，两个借用者拿到的是同一个对象（池里同一个对象放了两次）。
2. 上限 2，一次 `tryAcquire` 超时（返回 false）后仍在 `finally` 里 `release`：全部归还后可用许可为 3。
3. 两次借出后都因异常没有归还：可用许可 0，再借 `tryAcquire(50ms)` 返回 false。
4. id 1 被归还两次后池里有 3 项，之后有两个借用者同时持有 id 1。
5. 归还后原借用者手里的引用与新借用者拿到的是同一个对象。
6. 损坏的资源原样归还后，下一个借用者拿到 `broken=true` 的资源。
7. 带租约的池：两个租约拿到 id 1 与 2，第三次借用抛 `IllegalStateException（pool exhausted）`；同一个租约 `close` 两次后空闲资源 1 个；关闭后通过租约取资源抛 `IllegalStateException（lease closed）`；`try`-with-resources 中业务抛异常后空闲资源 2 个；id 2 损坏后归还被替换，之后池里是 id 1 与 3。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/LeaseLab.java`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 场景是单线程顺序排出来的，说明这些用法的结果，不说明并发下出现的频率。
- 示例里的租约只在通过 `lease.conn()` 取资源时检查是否已关闭；借用者把取到的对象另存一份引用，仍然可以绕过。真实的连接池用代理对象包住资源来解决这一点，没有在本实验中实现。
- 没有覆盖借用超时后的等待队列公平性、资源的空闲检测与最大寿命。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
