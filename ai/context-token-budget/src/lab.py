"""上下文预算：字符不是 token、不同词表差多少、按 token 截断会切坏字符、聊天模板的格式开销、按空格切词的长度估算。"""
import json
import re
import sys

import jinja2
import tiktoken
from tokenizers import Tokenizer

QWEN = sys.argv[1]                                   # 固定修订版的 Qwen2.5 分词器目录
cl100k = tiktoken.get_encoding("cl100k_base")
o200k = tiktoken.get_encoding("o200k_base")
qwen = Tokenizer.from_file(f"{QWEN}/tokenizer.json")


def out(key, text):
    print(f"{key}\t{text}")


def n(enc, s):
    if isinstance(enc, Tokenizer):
        return len(enc.encode(s, add_special_tokens=False).ids)
    return len(enc.encode(s))


SAMPLES = {
    "英文": "The quick brown fox jumps over the lazy dog while the context window fills up.",
    "中文": "上下文窗口按词元计算，字符数没有超出，请求仍然可能放不下。",
    "代码": "for (int i = 0; i < items.size(); i++) { total += items.get(i).price(); }",
    "数字": "订单号 20261030123456789，金额 1234567.89",
    "表情": "好的👍🏻收到🙏🏽马上处理🚀🚀🚀",
    "生僻字": "𠀀𠀁𠀂𪚥𱁬",
}

# 一、字符数与 token 数
for name, s in SAMPLES.items():
    out(f"chars.{name}", f"{len(s)} 个字符：cl100k_base {n(cl100k, s)}、o200k_base {n(o200k, s)}、Qwen2.5 {n(qwen, s)} 个 token")

# 二、字节级 BPE：按 token 截断再解码
emo = "收到👍🏻"
ids = cl100k.encode(emo)
pieces = [cl100k.decode_single_token_bytes(t) for t in ids]
cut = cl100k.decode(ids[:-1])
out("bpe.pieces", f"「{emo}」在 cl100k_base 中是 {len(ids)} 个 token，各自的字节：" + " ".join(p.hex() for p in pieces))
out("bpe.cut", f"去掉最后一个 token 再解码：{cut!r}（末尾 {cut.count(chr(0xFFFD))} 个替换字符 U+FFFD）")
out("bpe.utf8", f"「{emo}」是 {len(emo)} 个码点、{len(emo.encode())} 个 UTF-8 字节")

# 三、聊天模板的格式开销
cfg = json.load(open(f"{QWEN}/tokenizer_config.json"))
env = jinja2.Environment(trim_blocks=True, lstrip_blocks=True)
env.filters["tojson"] = lambda x, **k: json.dumps(x, ensure_ascii=False, **k)
tpl = env.from_string(cfg["chat_template"])


def render(msgs, gen=True):
    return tpl.render(messages=msgs, add_generation_prompt=gen, tools=None)


user_only = [{"role": "user", "content": "你好"}]
text = render(user_only)
content_tokens = n(qwen, "你好")
total = len(qwen.encode(text, add_special_tokens=False).ids)
out("chat.default-system", f"只发一条「你好」（{content_tokens} 个 token）：套用模板后 {total} 个 token；模板在没有 system 消息时自动加了一条=" + str("You are Qwen" in text))
msgs = [{"role": "system", "content": "你是助手。"}]
for i in range(20):
    msgs.append({"role": "user" if i % 2 == 0 else "assistant", "content": "好"})
rendered = len(qwen.encode(render(msgs), add_special_tokens=False).ids)
contents = sum(n(qwen, m["content"]) for m in msgs)
per = (rendered - contents - n(qwen, "<|im_start|>assistant\n")) / len(msgs)
out("chat.overhead", f"1 条 system + 20 条一字消息：内容共 {contents} 个 token，套用模板后 {rendered} 个；每条消息的格式开销 {per:.0f} 个 token，加上生成提示 {n(qwen, '<|im_start|>assistant' + chr(10))} 个")

# 四、按字符检查预算
WINDOW, RESERVE = 512, 128
text = "收到👍🏻" * 60
out("budget.chars", f"上下文 {WINDOW}、给输出预留 {RESERVE}：一段 {len(text)} 个字符的输入，按字符数检查「≤ {WINDOW - RESERVE}」通过={len(text) <= WINDOW - RESERVE}；实际 cl100k_base {n(cl100k, text)}（超出）、o200k_base {n(o200k, text)}、Qwen2.5 {n(qwen, text)} 个 token")
eng = "context " * 300
out("budget.english", f"反过来，{len(eng)} 个字符的英文按字符检查不通过，实际只有 o200k_base {n(o200k, eng)} 个 token")

# 五、按空格切词估算长度
def words(s):
    return len(re.split("\n| ", s))


ex = "问：退款多久到账？答：原路退回，一般三到五个工作日到账，节假日顺延。"
out("selector.words", f"一个中文示例：按空格和换行切出 {words(ex)} 个「词」，实际 Qwen2.5 {n(qwen, ex)} 个 token；按词数上限 200 能放进 {200 // words(ex)} 个示例，约 {200 // words(ex) * n(qwen, ex)} 个 token")
