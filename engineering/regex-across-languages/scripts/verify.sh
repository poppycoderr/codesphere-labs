#!/usr/bin/env bash
# 同一组正则用例在 Java（JDK 25）、Python 3.14、Node 24 下的结果：\d 与 \w 的范围、$ 与换行、整串匹配 API、命名分组与替换模板、split、忽略大小写、JS 的 lastIndex
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
  docker run --rm -e LANG=C.UTF-8 -v "$PWD:/w" -w /w "$J25" java -Dstdout.encoding=UTF-8 src/Regex.java
  docker run --rm -e PYTHONIOENCODING=utf-8 -v "$PWD:/w" -w /w "$PY" python src/regex.py
  docker run --rm -v "$PWD:/w" -w /w "$NODE" node src/regex.js
} 2>/dev/null | sort -t$'\t' -k2,2 -k1,1 >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "python_image: $PY" "node_image: $NODE"
cat "$f" >&2
# 输入固定，输出按用例名与语言排序：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
java	case.insensitive_unicode	false / false
node	case.insensitive_unicode	false / true
python	case.insensitive_unicode	False / True
java	case.insensitive_unicode_case	true（加 UNICODE_CASE）
java	digit.arabic_indic	false
node	digit.arabic_indic	false
python	digit.arabic_indic	True
java	digit.fullwidth	false
node	digit.fullwidth	false
python	digit.fullwidth	True
java	digit.unicode_character_class	true（加 UNICODE_CHARACTER_CLASS）
java	dollar.trailing_newline	true
node	dollar.trailing_newline	false
python	dollar.trailing_newline	True
java	dot.newline	false
node	dot.newline	false
python	dot.newline	False
node	global.lastIndex	[true,false,true,false]
node	global.lastIndex_without_g	[true,true,true,true]
java	named.syntax_java_style	2026
node	named.syntax_java_style	2026
python	named.syntax_java_style	异常 PatternError
python	named.syntax_python_style	2026
java	replace.backslash_template	2/1
node	replace.backslash_template	\2/\1
python	replace.backslash_template	10/2026
java	replace.named	10/2026
node	replace.named	10/2026
python	replace.named	10/2026
java	replace.numbered	10/2026
node	replace.numbered	10/2026
python	replace.numbered	$2/$1
java	split.dot	[]
node	split.dot	["","","","","",""]
python	split.dot	['', '', '', '', '', '']
java	split.trailing_empty	[a, b]
node	split.trailing_empty	["a","b","",""]
python	split.trailing_empty	['a', 'b', '', '']
java	version	25.0.4.1
node	version	v24.21.0
python	version	3.14.8
java	whole.match_api	false（matches）/ true（find）
node	whole.match_api	true（test）/ false（加 ^$）
python	whole.match_api	True（match）/ False（fullmatch）
java	word.chinese	false
node	word.chinese	false
python	word.chinese	True
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
