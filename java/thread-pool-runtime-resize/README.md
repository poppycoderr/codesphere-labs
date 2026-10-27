# 运行时调整线程池参数

对应文章：[thread-pool-runtime-resize.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/thread-pool-runtime-resize.md)。

`src/Lab.java` 在 JDK 25 上验证运行时修改 ThreadPoolExecutor 参数与钩子的几条约束：core 与 max 的先后顺序、缩容对在途与排队任务的影响、afterExecute 对 execute 与 submit 任务的差别、钩子抛错与 CallerRunsPolicy。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
