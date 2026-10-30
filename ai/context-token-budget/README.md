# 上下文预算：字符不是 token

对应文章：[context-token-budget.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/context-token-budget.md)。

`src/lab.py` 用 tiktoken 的 cl100k_base 与 o200k_base、以及 Qwen2.5-0.5B-Instruct 固定修订版的 `tokenizer.json` 计数，依次比较：几类文本的字符数与 token 数；字节级 BPE 按 token 截断后解码；用 jinja2 渲染 `tokenizer_config.json` 里的对话模板，算出默认 system 消息与每条消息的格式开销；按字符数与按空格切词估算预算的偏差。词表文件与分词器下载到 `$LABS_CACHE`。

```bash
make verify     # 需要 Docker；约 10 秒（首次约 1 分钟）
make evidence   # 重新采集 evidence/
make clean
```
