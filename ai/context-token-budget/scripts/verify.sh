#!/usr/bin/env bash
# 上下文预算：字符与 token、三种词表、按 token 截断切坏字符、聊天模板开销、按字符或空格估算的预算（tiktoken 0.14.0、tokenizers 0.23.3、Qwen2.5 分词器固定修订版）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器；词表与分词器文件下载到 $LABS_CACHE；首次约 1 分钟，之后约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
require curl
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip" "$LABS_CACHE/tiktoken"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
REV="7ae557604adf67be50417f59c2c2f167def9a775"
Q="$LABS_CACHE/qwen2.5-tokenizer-$REV"; mkdir -p "$Q"
for f in tokenizer.json tokenizer_config.json; do
  [ -s "$Q/$f" ] || curl -fsSL -o "$Q/$f" "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct/resolve/$REV/$f" || fail "下载 $f 失败"
done
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -v "$LABS_CACHE/tiktoken:/tk" -v "$Q:/qwen:ro" -e TIKTOKEN_CACHE_DIR=/tk -w /w "$PY" \
  sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py /qwen' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "$(tr '\n' ' ' < requirements.txt)" "qwen2.5_tokenizer_revision: $REV" \
  "tokenizer_sha256: $(shasum -a 256 "$Q/tokenizer.json" | cut -c1-16)…"
cat "$f" >&2
# 分词结果由固定版本的词表决定，输出是确定的
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
chars.英文	78 个字符：cl100k_base 16、o200k_base 16、Qwen2.5 16 个 token
chars.中文	29 个字符：cl100k_base 28、o200k_base 22、Qwen2.5 19 个 token
chars.代码	73 个字符：cl100k_base 25、o200k_base 25、Qwen2.5 25 个 token
chars.数字	35 个字符：cl100k_base 17、o200k_base 17、Qwen2.5 33 个 token
chars.表情	15 个字符：cl100k_base 29、o200k_base 15、Qwen2.5 11 个 token
chars.生僻字	5 个字符：cl100k_base 17、o200k_base 17、Qwen2.5 16 个 token
bpe.pieces	「收到👍🏻」在 cl100k_base 中是 8 个 token，各自的字节：e694b6 e588b0 f09f 91 8d f09f 8f bb
bpe.cut	去掉最后一个 token 再解码：'收到👍�'（末尾 1 个替换字符 U+FFFD）
bpe.utf8	「收到👍🏻」是 4 个码点、14 个 UTF-8 字节
chat.default-system	只发一条「你好」（1 个 token）：套用模板后 30 个 token；模板在没有 system 消息时自动加了一条=True
chat.overhead	1 条 system + 20 条一字消息：内容共 23 个 token，套用模板后 131 个；每条消息的格式开销 5 个 token，加上生成提示 3 个
budget.chars	上下文 512、给输出预留 128：一段 240 个字符的输入，按字符数检查「≤ 384」通过=True；实际 cl100k_base 480（超出）、o200k_base 180、Qwen2.5 180 个 token
budget.english	反过来，2400 个字符的英文按字符检查不通过，实际只有 o200k_base 301 个 token
selector.words	一个中文示例：按空格和换行切出 1 个「词」，实际 Qwen2.5 24 个 token；按词数上限 200 能放进 200 个示例，约 4800 个 token
EXPECTED
log "全部通过，输出在 ${OUT}"
