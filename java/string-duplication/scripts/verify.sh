#!/usr/bin/env bash
# 内容相同的字符串占多少内存：200 万条记录、100 种取值，各建各的、intern、自己的 Map 归一、G1 字符串去重，以及多一个汉字的影响
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，子进程堆上限 1 GB；约 1 分钟。堆占用是近似测量，只断言范围
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
# 第二列是每条记录的字段平均占用的字节数（已减去引用数组），第三列是构建这 200 万个字段的毫秒数
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/StringLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
val() { awk -F'\t' -v k="$1" '$1==k {print $2}' "$f"; }
between() { awk -v a="$1" -v lo="$2" -v hi="$3" 'BEGIN{exit !(a>=lo && a<=hi)}' || fail "$4（$1 不在 $2—$3）"; log "通过：$4（$1 在 $2—$3）"; }
between "$(val plain.bytes_per_record)" 56 72 "各建各的：每个字段约 64 字节"
between "$(val intern.bytes_per_record)" -2 4 "intern：每个字段接近 0 字节"
between "$(val map.bytes_per_record)" -2 4 "自己的 Map 归一：每个字段接近 0 字节"
between "$(val dedup.bytes_per_record)" 20 32 "G1 字符串去重：每个字段约 24 字节（String 对象还在，只共享了底层数组）"
between "$(val plain_cjk.bytes_per_record)" 80 96 "多一个汉字：每个字段约 88 字节"
log "全部通过，输出在 $OUT"
