# 受理不等于完成

对应文章：[async-acceptance.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/async-acceptance.md)。

`src/AcceptLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。它用 JDK 自带的 `HttpServer` 提供一个返回 202 的任务接口（`POST /jobs`、`GET /jobs/{id}`），用 `HttpClient` 访问；后台执行的每一步（取任务、完成、崩溃重启、时间前进）由测试代码手动推进，所以各场景的顺序是确定的，`scripts/verify.sh` 把输出与预期逐行比较。

1. 提交返回 202，任务随后失败；
2. 提交时客户端超时后重试：不带幂等键与带同一个 `Idempotency-Key`；
3. 轮询时把「不是 RUNNING」当成结束，与按终态集合判断；
4. 进程重启：任务表在内存里、任务表持久化但没有租约、执行中的任务带租约；
5. 任务成功之后到达迟到的进度上报：无条件写入与只允许向前的状态转换；
6. 租约过期、任务被第二个执行者接手之后，旧执行者上报完成：只检查状态与同时检查执行序号。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
