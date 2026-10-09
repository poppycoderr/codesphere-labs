# 备份请求

对应文章：[hedged-requests.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/hedged-requests.md)。

`src/HedgeLab.java` 是一个固定种子的离散事件模拟，在固定 digest 的 temurin 25 容器里以源码方式运行。10 个实例，每个 8 个处理槽和一个等待队列；98% 的请求处理 10 毫秒，2% 的请求处理 300 毫秒（与实例无关的偶发变慢），总容量约每秒 5060 个请求。请求随机到达，按「两次随机选择」分给实例。

在每秒 2500 与每秒 4500 两种到达速率下，各比较四种做法：不发备份；30 毫秒没有返回就向另一个实例再发一份；同上但备份请求不超过全部请求的 5%；一开始就发两份。先回来的那一份算作响应，另一份不取消、照常执行完。每种记录 p50、p99、p99.9、备份请求的比例、服务端实际执行次数与请求数之比、两份都执行完的请求比例。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒
make evidence
make clean
```
