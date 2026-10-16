#!/usr/bin/env bash
# 反斜杠的三层转义在 Java、Python、JavaScript 下的结果：源码字符串、正则、替换模板，以及 quote 与 escape
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：三个固定 digest 的语言容器；约 15 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
NODE="node:24.21.0-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
f="$OUT/output.tsv"
{
  docker run --rm -e LANG=C.UTF-8 -v "$PWD:/w" -w /w "$J25" java -Dstdout.encoding=UTF-8 src/Escape.java
  docker run --rm -e PYTHONIOENCODING=utf-8 -v "$PWD:/w" -w /w "$PY" python src/escape.py
  docker run --rm -v "$PWD:/w" -w /w "$NODE" node src/escape.js
} 2>/dev/null | sort -t$'\t' -k2,2 -k1,1 >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "python_image: $PY" "node_image: $NODE"
cat "$f" >&2
# 输入固定，输出按用例名与语言排序：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
java	backslash.literal_replace	String.replace 不用正则：C:/temp/a.txt
node	backslash.literal_replace	replace 传字符串只替换第一个：C:/temp\a.txt；replaceAll：C:/temp/a.txt
python	backslash.literal_replace	str.replace 不用正则：C:/temp/a.txt
node	backslash.new_regexp	new RegExp("\\\\")（源码 4 个反斜杠）：C:/temp/a.txt
java	backslash.regex_source	"\\\\"（源码 4 个反斜杠 → 字符串 2 个 → 正则匹配 1 个）：C:/temp/a.txt
node	backslash.regex_source	/\\/g（字面量里 2 个反斜杠 → 匹配 1 个）：C:/temp/a.txt
python	backslash.regex_source	r"\\"（原始字符串 2 个反斜杠 → 正则匹配 1 个）：C:/temp/a.txt
java	dot.quoted	Pattern.quote("a.b") 匹配 axb：false
node	dot.quoted	RegExp.escape("a.b") 匹配 axb：false
python	dot.quoted	re.escape('a.b') 匹配 axb：False
java	dot.unescaped	a.b 匹配 axb：true
node	dot.unescaped	a.b 匹配 axb：true
python	dot.unescaped	a.b 匹配 axb：True
java	replacement.backslash_escaped	替换串是两个反斜杠（源码写 "\\\\"）：a\b
java	replacement.backslash_quoted	Matcher.quoteReplacement("\\")：a\b
java	replacement.backslash_raw	替换串是一个反斜杠（源码写 "\\"）：异常 IllegalArgumentException：character to be escaped is missing
python	replacement.backslash_user	把 {price} 替换成用户输入的 "\5"：异常 PatternError：invalid group reference 5 at position 1
node	replacement.dollar_amp	用户输入 "$&"：价格 {price}
java	replacement.dollar_quoted	Matcher.quoteReplacement：价格 $5
java	replacement.dollar_raw	把 {price} 替换成用户输入的 "$5"：异常 IndexOutOfBoundsException：No group 5
node	replacement.dollar_raw	把 {price} 替换成用户输入的 "$5"：价格 $5
python	replacement.dollar_raw	替换成 "$5"：价格 $5
node	replacement.function	替换参数传函数，返回 "$&"：价格 $&
python	replacement.function	替换参数传函数：价格 \5
java	source.length	C:\temp\a.txt，长度 13
node	source.length	C:\temp\a.txt，长度 13
python	source.length	C:\temp\a.txt，长度 13
python	source.non_raw_warning	非原始字符串 "\d"：长度 2，编译时警告 ['SyntaxWarning']
java	textblock.backslash	文本块里的 \d 仍是转义：true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
