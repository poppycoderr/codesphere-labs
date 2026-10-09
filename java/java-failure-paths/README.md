# 出错路径上丢掉的信息

对应文章：[java-failure-paths.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/java-failure-paths.md)。

`src/FailureLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。每个场景都是「出了错，但调用方或日志看不到完整的信息」：`finally` 里的 `return`；关闭资源时的异常；捕获后换一个异常抛出；复用同一个异常对象；热点代码里反复抛出的隐式异常；线程池、周期任务与 `CompletableFuture` 里的异常；没有放进 `finally` 的解锁。

热点代码的场景用子进程运行两次：默认参数，与 `-XX:-OmitStackTraceInFastThrow`。`javac` 的提示通过 `javax.tools` 在程序里编译一小段源码取得。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒
make evidence
make clean
```
