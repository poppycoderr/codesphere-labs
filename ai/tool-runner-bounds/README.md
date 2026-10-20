# 工具调用循环是一个有界解释器

对应文章：[tool-runner-bounds.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/tool-runner-bounds.md)。

`src/lab.py` 在固定 digest 的 `python:3.14.8-slim` 容器里运行，只用标准库，容器无网络。模型是按脚本返回动作的假实现，工具是内存里的假商店（搜索、下单、退款，另有一个没有登记为工具的内部清理函数）。同一组动作脚本分别交给两个运行器：朴素的运行器（按名字在全局里找函数、只处理第一个调用、不校验参数、超时就重试、没有步数上限，实验里给 50 步防止跑不完），以及有界的运行器（工具允许列表加本次任务允许的子集、参数类型与范围校验、逐个处理同一步的调用、5 步上限、有副作用的调用用动作 ID 作幂等键、结束前检查必需的结果）。最后对比审核服务抛异常时「当作 None」与「当作未知」两种处理。

## 快速运行

```bash
make verify     # 需要 Docker；约 5 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
