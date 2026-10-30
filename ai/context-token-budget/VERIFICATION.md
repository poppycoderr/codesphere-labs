# 验证记录：上下文预算：字符不是 token

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 表情消息 15 个字符 | cl100k 29、o200k 15、Qwen2.5 11 个 token |
| 数字 35 个字符 | cl100k 与 o200k 17、Qwen2.5 33（逐位切分） |
| 按 token 截断「收到👍🏻」 | 解码末尾出现 U+FFFD |
| Qwen2.5 模板，只发「你好」 | 自动加 system 消息，1 → 30 个 token |
| 每条消息的格式开销 | 5 个 token，生成提示 3 个 |
| 240 个字符按字符检查 ≤ 384 | 通过；cl100k 480 个 token 超出 |
| 中文示例按空格切词 | 1 个词，实际 24 个 token |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖三种公开词表；商业模型的实际计费 token 以供应商返回的 usage 为准，可能包含不公开的格式开销。
- 对话模板用 jinja2 按 tokenizer_config.json 渲染，环境参数（trim_blocks、lstrip_blocks）参照 transformers 的做法；推理服务的模板实现可能有细微差别。
- 按空格切词的长度函数是常见示例选择器的默认写法，实验自行实现，没有引入具体框架。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-30 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
