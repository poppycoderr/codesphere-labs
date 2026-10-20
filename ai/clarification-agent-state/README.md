# 等待人工澄清的代理

对应文章：[clarification-agent-state.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/clarification-agent-state.md)。

`src/lab.py` 在固定 digest 的 `python:3.14.8-slim` 容器里运行，只用标准库，容器无网络。模型是确定性的假实现：根据已确认的答案判断还缺哪个问题（问题 ID 稳定），缺口补齐后给出验收草案。先运行一个让同一模型自问自答的反例，再运行持久状态机：任务状态、已确认答案、当前等待的问题、已提问次数与截止时间存在 SQLite 中，回答写入按回答 ID 去重的收件表；用新连接模拟进程重启；时钟由参数注入以验证过期。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
