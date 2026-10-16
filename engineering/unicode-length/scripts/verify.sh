#!/usr/bin/env bash
# 一个字符串有多长：字节、码点、UTF-16 单元与字形在 Java（JDK 25）、Python 3.14、Node 24 下的结果，以及截断、反转、规范化与大小写转换
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
  docker run --rm -e LANG=C.UTF-8 -v "$PWD:/w" -w /w "$J25" java -Dstdout.encoding=UTF-8 src/Uni.java
  docker run --rm -e PYTHONIOENCODING=utf-8 -v "$PWD:/w" -w /w "$PY" python src/uni.py
  docker run --rm -v "$PWD:/w" -w /w "$NODE" node src/uni.js
} 2>/dev/null | sort -t$'\t' -k2,2 -k1,1 >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "python_image: $PY" "node_image: $NODE"
cat "$f" >&2
# 输入固定，输出按用例名与语言排序：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
java	cut.first_unit	substring(0, 1) 得到 U+D83D，是孤立代理：true；编码成 UTF-8 后变成了 "?"：true
node	cut.first_unit	slice(0, 1) 得到 U+D83D，isWellFormed=false；TextEncoder 编码成 ef bf bd（U+FFFD）
python	cut.first_unit	s[:1] 得到 U+1F468：按码点切，一家三口只剩第一个人
java	equals.nfc_nfd	é（预组合）equals e+重音：false；NFC 规范化后：true
node	equals.nfc_nfd	é（预组合）=== e+重音：false；NFC 规范化后：true
python	equals.nfc_nfd	é（预组合）== e+重音：False；NFC 规范化后：True
java	len.e_acute_nfc	length()=1 codePoints=1 utf8=2 graphemes=1
node	len.e_acute_nfc	length=1 codePoints=1 utf8=2 graphemes=1
python	len.e_acute_nfc	len()=1 utf16=1 utf8=2 graphemes=标准库不支持
java	len.e_acute_nfd	length()=2 codePoints=2 utf8=3 graphemes=1
node	len.e_acute_nfd	length=2 codePoints=2 utf8=3 graphemes=1
python	len.e_acute_nfd	len()=2 utf16=2 utf8=3 graphemes=标准库不支持
java	len.family_zwj	length()=8 codePoints=5 utf8=18 graphemes=1
node	len.family_zwj	length=8 codePoints=5 utf8=18 graphemes=1
python	len.family_zwj	len()=5 utf16=8 utf8=18 graphemes=标准库不支持
java	len.flag_cn	length()=4 codePoints=2 utf8=8 graphemes=1
node	len.flag_cn	length=4 codePoints=2 utf8=8 graphemes=1
python	len.flag_cn	len()=2 utf16=4 utf8=8 graphemes=标准库不支持
java	len.grin	length()=2 codePoints=1 utf8=4 graphemes=1
node	len.grin	length=2 codePoints=1 utf8=4 graphemes=1
python	len.grin	len()=1 utf16=2 utf8=4 graphemes=标准库不支持
java	len.thumbs_skin	length()=4 codePoints=2 utf8=8 graphemes=1
node	len.thumbs_skin	length=4 codePoints=2 utf8=8 graphemes=1
python	len.thumbs_skin	len()=2 utf16=4 utf8=8 graphemes=标准库不支持
java	len.zhong	length()=1 codePoints=1 utf8=3 graphemes=1
node	len.zhong	length=1 codePoints=1 utf8=3 graphemes=1
python	len.zhong	len()=1 utf16=1 utf8=3 graphemes=标准库不支持
java	reverse.flag	StringBuilder.reverse() 反转国旗：[1f1f3, 1f1e8]
node	reverse.flag	[...s].reverse() 反转国旗：["1f1f3","1f1e8"]
python	reverse.flag	s[::-1] 反转国旗：['1f1f3', '1f1e8']
java	upper.sharp_s	"straße".toUpperCase()：STRASSE，长度 6 → 7
node	upper.sharp_s	"straße".toUpperCase()：STRASSE，长度 6 → 7
python	upper.sharp_s	'straße'.upper()：STRASSE，长度 6 → 7
java	version	25.0.4.1
node	version	v24.21.0
python	version	3.14.8
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
