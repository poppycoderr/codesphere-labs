#!/usr/bin/env bash
# 压缩对象头：JDK 25 与 26 上开关 UseCompactObjectHeaders 时的字段布局、各类对象的实际大小、100 万条目 HashMap 的堆占用
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25、26 容器，Serial GC，堆 2 GB；约 2 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
J26="eclipse-temurin:26-jdk@sha256:c7a2be9d6fbbc4984b855cee02359b0e808f0ab5bcbede818970417e2407ab64"
for jdk in 25 26; do
  img=$([ $jdk = 25 ] && echo "$J25" || echo "$J26")
  docker run --rm "$img" sh -c 'java -version 2>&1 | head -1; java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E " (UseCompactObjectHeaders|UseCompressedClassPointers|ObjectAlignmentInBytes) "' >"$OUT/jdk$jdk-flags.txt"
  for mode in off on; do
    flag=$([ $mode = on ] && echo "-XX:+UseCompactObjectHeaders" || echo "-XX:-UseCompactObjectHeaders")
    docker run --rm -v "$PWD:/w" -w /w "$img" java -XX:+UseSerialGC -Xmx2g -Xlog:disable -Xlog:all=error "$flag" src/ObjectSizes.java 2>/dev/null >"$OUT/jdk$jdk-$mode.tsv"
  done
done
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "jdk26_image: $J26"
cat "$OUT"/jdk*-flags.txt "$OUT"/jdk25-*.tsv >&2
for jdk in 25 26; do
  expect_regex "$OUT/jdk$jdk-flags.txt" "bool UseCompactObjectHeaders += false .*\{product" "JDK $jdk：UseCompactObjectHeaders 是正式参数，默认关闭"
done
python3 - "$OUT" <<'PY'
import re, sys
out = sys.argv[1]
def sizes(f):
    t = open(f, encoding="utf-8").read()
    s = {k: float(v) for k, v in re.findall(r"^size\.(\S+)\t.*每个 ([\d.]+) 字节", t, re.M)}
    s["hashmap"] = float(re.search(r"堆占用增加 ([\d.]+) MB", t)[1])
    s["field"] = int(re.search(r"OneInt\.a 的偏移 (\d+)", t)[1])
    return s
for jdk in ("25", "26"):
    off, on = sizes(f"{out}/jdk{jdk}-off.tsv"), sizes(f"{out}/jdk{jdk}-on.tsv")
    assert off["field"] == 12 and on["field"] == 8, (off["field"], on["field"])
    for k in ("Object", "Long", "TwoInts", "NodeLike", "byte[4]"):
        assert off[k] - on[k] == 8, (jdk, k, off[k], on[k])            # 头省下的 4 字节加上对齐，正好少 8 字节
    for k in ("Integer", "OneInt", "ThreeInts", "byte[0]", "byte[8]"):
        assert off[k] == on[k], (jdk, k, off[k], on[k])                # 省下的 4 字节被对齐吃掉，大小不变
    assert on["hashmap"] < off["hashmap"] * 0.9, (off["hashmap"], on["hashmap"])
    print(f"通过：JDK {jdk} 上 HashMap<Long, Order> 从 {off['hashmap']} MB 降到 {on['hashmap']} MB（{(1 - on['hashmap'] / off['hashmap']) * 100:.1f}%）")
PY
log "全部通过，输出在 $OUT"
