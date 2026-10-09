# 验证记录：出错路径上丢掉的信息

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `try { return 1; } finally { return 2; }` | 返回 2 |
| `try { throw …; } finally { return -1; }` | 方法正常返回 -1，调用方看不到异常 |
| `javac` 对上面的写法 | 默认没有任何提示；`-Xlint:finally` 给出 `finally clause cannot complete normally` |
| 手写 `finally` 关闭，主体与关闭都失败 | 调用方收到关闭的 `IOException`，`getSuppressed()` 为空，主体的异常不见了 |
| try-with-resources，同样的情况 | 调用方收到主体的异常，关闭的异常在 `getSuppressed()` 里 |
| 三个资源、第三个打开失败 | 打开A → 打开B → 关闭B → 关闭A |
| 手写：两个资源先都打开再进 `try`，第二个打开失败 | A 没有被关闭 |
| `throw new RuntimeException("…" + e.getMessage())` | `getCause()` 为 null，调用栈里找不到原来出错的方法 |
| `"失败：" + e.getMessage()` 遇到没有消息的异常 | `失败：null` |
| `static final` 的异常对象从两个方法抛出 | 第一帧都是 `<clinit>` |
| 同一处隐式空指针异常抛 20 万次，默认参数 | 出现调用栈为空、消息为 null 的异常，之后都是同一个对象 |
| 同上，`-XX:-OmitStackTraceInFastThrow` | 每一次都带调用栈 |
| `submit` 与 `execute` 各提交一个抛异常的任务 | 未捕获异常处理器只收到 `execute` 的那个 |
| `scheduleAtFixedRate` 第 3 次执行抛异常 | 之后不再执行，`isDone` 为 true，处理器没有收到 |
| `completeExceptionally(ISE)` 后 `exceptionally` | 收到 `IllegalStateException` |
| `supplyAsync` 里抛 ISE 后 `exceptionally`；或经过一级 `thenApply` | 收到 `CompletionException`，原因才是 ISE |
| `join()` 与 `get()` | 分别抛 `CompletionException` 与 `ExecutionException` |
| 没有任何后续处理的失败任务 | 没有任何输出 |
| `lock(); 处理; unlock();` 处理中途抛异常 | 线程结束后锁仍被占着，其他线程 `tryLock` 失败 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 被省掉调用栈的异常是 C2 编译后的行为，出现在第几次取决于编译时机；实验只断言「20 万次里出现过」。只对 JVM 隐式抛出的几种异常（空指针、数组越界、除零、类型转换、数组存储）有效，代码里 `new` 出来的异常不受影响。
- 空指针异常的消息里出现 `<local3>` 是因为源码方式运行时没有带调试信息编译；用 `-g`（Maven、Gradle 的默认设置）编译时会显示变量名。
- 线程池的未捕获异常处理器在工作线程退出时才被调用，可能晚于 `awaitTermination` 返回；实验等到处理器执行完再输出。
- 没有覆盖日志框架自己的行为（例如异步日志丢弃、调用栈截断），也没有覆盖 Spring 的事务回滚规则。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-10 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
