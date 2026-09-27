# 重试放大与超时预算

对应文章：[timeout-budgets-and-retries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/timeout-budgets-and-retries.md)。

单文件程序 `src/RetryChain.java`：驱动程序 → A（9101）→ B（9102）→ C（9103），都用 JDK 自带的 `HttpServer`，在同一个 JVM 里运行。

| 场景 | C 的行为 | 对比 |
|---|---|---|
| `fail.*` | 立刻返回 503 | 每层都尝试 3 次；只有入口尝试 3 次；每层尝试 3 次但重试不超过请求数的 10% |
| `slow.inverted`、`slow.deadline` | 处理 2.5 秒 | 用户等 1 秒、A→B 2 秒、B→C 3 秒（超时倒挂）；是否通过 `X-Deadline` 请求头传递截止时间 |
| `slow.inverted-retries` | 处理 2.5 秒 | 超时倒挂，并且每层都尝试 3 次 |

## 快速运行

```bash
make verify     # 需要 JDK 21；约 45 秒
make evidence
make clean
```
