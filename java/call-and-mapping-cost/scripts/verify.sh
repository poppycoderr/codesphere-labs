#!/usr/bin/env bash
# 调用与映射的开销：JMH 测直接调用、JDK 代理、反射、Byte Buddy 与 Spring CGLIB 代理，以及手写、MapStruct、BeanUtils 三种映射；
# 旧版 CGLIB、Byte Buddy 与 Javassist 在当前 JDK 上能否生成类；MapStruct 的 unmappedTargetPolicy=ERROR 让漏映射的字段编译失败
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
# 资源：宿主机 JDK 21 与 Maven；约 3 分钟（首次需要下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-target/run}"
require_java 21
require mvn python3
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete

log "构建（mvn package）"
mvn -B -q -Dstyle.color=never package >"$OUT/maven-package.log" 2>&1 || { cat "$OUT/maven-package.log"; fail "构建失败"; }
ls target/generated-sources/annotations/labs/mapping/ >"$OUT/mapstruct-generated.txt"

log "JMH：每个基准 1 个 fork、预热 3 × 1 秒、测量 5 × 1 秒"
java -jar target/benchmarks.jar -f 1 -wi 3 -w 1s -i 5 -r 1s -rf csv -rff "$PWD/target/jmh.csv" >"$OUT/jmh.log" 2>&1
normalize_paths <"$OUT/jmh.log" >"$OUT/jmh.log.tmp" && mv "$OUT/jmh.log.tmp" "$OUT/jmh.log"
cp target/jmh.csv "$OUT/jmh.csv"

log "旧版字节码库在当前 JDK 上"
compat() {
  local name="$1" main="$2"; shift 2
  local cp
  cp=$(printf '%s:' "$@")
  { java -cp "$cp" "compat/$main.java" 2>&1 || true; } | head -3 | normalize_paths >"$OUT/compat-$name.txt"
}
compat cglib-3.2.5 OldCglib "$(maven_jar cglib:cglib:3.2.5)" "$(maven_jar org.ow2.asm:asm:5.2)"
compat byte-buddy-1.6.14 OldByteBuddy "$(maven_jar net.bytebuddy:byte-buddy:1.6.14)"
compat javassist-3.29.2 JavassistGen "$(maven_jar org.javassist:javassist:3.29.2-GA)"

log "MapStruct：目标对象多一个字段、unmappedTargetPolicy=ERROR"
rm -rf target/bad && mkdir -p target/bad
{ javac -d target/bad -proc:full -cp "$(maven_jar org.mapstruct:mapstruct:1.6.3)" \
    -processorpath "$(maven_jar org.mapstruct:mapstruct-processor:1.6.3)" \
    -Amapstruct.unmappedTargetPolicy=ERROR compat/bad-mapper/BadMapper.java 2>&1 && echo "编译成功" || echo "编译失败"; } \
  | normalize_paths >"$OUT/bad-mapper.txt"

write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')" "jmh: 1.37" "mapstruct: 1.6.3" "spring-beans: 7.0.9" "byte-buddy: 1.18.14"
python3 - "$OUT" <<'PY' | tee "$OUT/summary.tsv"
import csv, sys
out = sys.argv[1]
rows = list(csv.DictReader(open(f"{out}/jmh.csv", encoding="utf-8")))
for r in rows:
    name = r["Benchmark"].replace("labs.", "")
    score = float(r["Score"])
    err = float(r["Score Error (99.9%)"])
    print(f"jmh.{name}\t{score:.2f} ± {err:.2f} ns/op（约 {1e9 / score / 1e6:,.1f} 百万次/秒）")
PY
f="$OUT/summary.tsv"
expect_line "$OUT/mapstruct-generated.txt" "UserMapperImpl.java" "MapStruct 在编译期生成 UserMapperImpl"
expect_regex "$OUT/compat-cglib-3.2.5.txt" "InaccessibleObjectException" "CGLIB 3.2.5 在 JDK 21 上失败：模块强封装"
expect_regex "$OUT/compat-byte-buddy-1.6.14.txt" "UnsupportedOperationException: Cannot define class using reflection" "Byte Buddy 1.6.14 在 JDK 21 上无法用反射注入类"
expect_line "$OUT/compat-javassist-3.29.2.txt" "ok hello" "Javassist 3.29.2 正常生成类"
expect_regex "$OUT/bad-mapper.txt" "Unmapped target property: \"nickname\"" "漏映射字段报错"
expect_line "$OUT/bad-mapper.txt" "编译失败" "unmappedTargetPolicy=ERROR 让编译失败"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
ns = {k: float(v) for k, v in re.findall(r"^jmh\.\w+\.\w+\.(\w+)\t([\d.]+)", t, re.M)}
assert ns["beanUtils"] > 10 * ns["mapstruct"], ns                  # 反射映射比生成代码慢一个数量级以上
assert ns["reflection"] > ns["direct"] and ns["jdkProxy"] > ns["direct"], ns
assert max(ns[k] for k in ("direct", "jdkProxy", "reflection", "byteBuddy", "springCglib")) < 100, ns   # 调用开销都在百纳秒以内
print("通过：" + "，".join(f"{k} {v:.2f} ns" for k, v in ns.items()))
PY
log "全部通过，输出在 $OUT"
