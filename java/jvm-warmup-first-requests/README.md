# 进程刚启动时的前几个请求

对应文章：[jvm-warmup-first-requests.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/jvm-warmup-first-requests.md)。

`src/WarmupLab.java` 先编译成 jar，在固定 digest 的 temurin 25 容器（限 2 个 CPU）里运行。处理函数做的事接近一个小接口：拼字符串、正则提取、放进有序 Map、流式拼接、格式化时间、算 SHA-256、`String.format`，用到的类和静态字段在第一次调用时才初始化。

父进程用每组 JVM 参数各启动 5 个全新的子进程，子进程连续调用处理函数一万次并记录每次的耗时，父进程对各项指标取 5 次的中位数：

- `default`：默认参数；
- `xint`：`-Xint`，只用解释器；
- `c1_only`：`-XX:TieredStopAtLevel=1`，只用 C1；
- `aot_cache`：先做一次训练运行（`-XX:AOTCacheOutput`），子进程带 `-XX:AOTCache` 启动；
- `prewarm`：默认参数，子进程先用另一批输入调用 3000 次，再开始记录。

`launch_to_first_response_ms` 由父进程测量：从发起启动子进程，到读到子进程处理完第一个请求后输出的标记。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟。计时类实验，运行期间不要同时跑其他负载
make evidence
make clean
```
