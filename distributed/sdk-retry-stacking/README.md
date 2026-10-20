# SDK 自带重试与应用层重试叠加

对应文章：[timeout-budgets-and-retries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/timeout-budgets-and-retries.md)。

用标准库写一个本地假服务，按场景返回 503、429、409、400 或延迟响应，统计 openai Python SDK 一次调用实际发出的请求数，以及应用层再包一层重试后的总数。API Key 是演示值，不访问任何真实服务。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里安装 openai 3.35.1，运行并逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
