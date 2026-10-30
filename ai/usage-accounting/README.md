# 用量记账

对应文章：[usage-accounting.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/usage-accounting.md)。

`src/lab.py` 只用标准库：一个假供应商按响应 ID 流式返回文本片段，最后发一个 usage 事件，并在内部保存按实际生成量计费的账单（包括超时与客户端断开的响应）；应用侧先用朴素写法（只在成功时从 usage 事件入账、每次尝试都追加回调）记账，再用 SQLite 账本按响应 ID 幂等入账、对超时与断开的响应事后查询用量，两种账本都与供应商账单对照。

```bash
make verify     # 需要 Docker；约 5 秒
make evidence   # 重新采集 evidence/
make clean
```
