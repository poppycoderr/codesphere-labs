# 开放与闭合负载模型

对应文章：[open-vs-closed-load-model.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/open-vs-closed-load-model.md)。

`src/LoadModelLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。被测服务在进程内模拟：16 个并发处理槽，每次处理 5 毫秒，测试的第 5—7 秒整体卡住。同一个服务用四种发压方式各测 12 秒：

1. 闭合模型：10 个虚拟用户，收到响应后立刻发下一个；
2. 开放模型：每秒 1000 个请求按时间表到达，每个请求一个虚拟线程；
3. 单线程按时间表发送（每秒 100 个）：上一个没回来下一个就发不出去；同一次运行分别从「实际发送时刻」和「预定发送时刻」计时；
4. 发压能力不足：目标每秒 2000 个，只有 4 个同步发送线程，服务不卡顿。

每种方式输出样本数、实际速率、卡顿窗口内发出的请求数，以及两种计时起点下超过 100 毫秒的样本数与各分位数（毫秒）。脚本只断言范围。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
