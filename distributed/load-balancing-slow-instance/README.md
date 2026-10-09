# 负载均衡遇到异常实例

对应文章：[load-balancing-slow-instance.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/load-balancing-slow-instance.md)。

`src/LbLab.java` 是一个固定种子的离散事件模拟，在固定 digest 的 temurin 25 容器里以源码方式运行。10 个实例，每个有 8 个处理槽和一个先进先出的等待队列，正常处理时间 10 毫秒；请求以平均每秒 4000 个的速率随机到达（指数分布的间隔），模拟 30 秒。实例 3 异常，分两种情况：

- 变慢：处理时间变成 100 毫秒；
- 快速失败：1 毫秒返回错误。

比较五种选择策略：轮询、随机、最少在途请求（并列时随机）、两次随机选择（随机挑两个实例，取在途较少的）、最少在途加连续失败摘除（连续 5 次失败摘除 5 秒）。每种记录送到异常实例的请求比例、p50、p99、最大延迟、超过 1 秒的比例与错误比例。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒
make evidence
make clean
```
