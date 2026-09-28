#!/usr/bin/env bash
# Maven scope：compile、runtime、test 三种类路径各包含哪些依赖；provided 依赖在只按 runtime 类路径运行时找不到
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
# 资源：宿主机 JDK 21 与 Maven；约 30 秒（首次需要下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-target/run}"
require_java 21
require mvn
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
list() {
  mvn -B -q -Dstyle.color=never dependency:list -DincludeScope="$1" -DoutputFile="$PWD/target/list-$1.txt" >/dev/null
  grep -E '^ +[a-z]' "target/list-$1.txt" | sed -E 's/ -- module .*//; s/^ +//' | sort >"$OUT/classpath-$1.txt"
}
mvn -B -q -Dstyle.color=never compile >"$OUT/maven-compile.log" 2>&1 || { cat "$OUT/maven-compile.log"; fail "编译失败"; }
for s in compile runtime test; do list "$s"; done
mvn -B -q -Dstyle.color=never dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="$PWD/target/runtime.cp" >/dev/null
{ java -cp "target/classes:$(cat target/runtime.cp)" labs.App 2>&1 || true; } | head -2 | normalize_paths >"$OUT/run-with-runtime-classpath.txt"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')" "maven-dependency-plugin: 3.8.1"
cat "$OUT"/classpath-*.txt "$OUT/run-with-runtime-classpath.txt" >&2
expect_line "$OUT/classpath-compile.txt" "org.mapstruct:mapstruct:jar:1.6.3:provided" "provided 在编译类路径上"
expect_line "$OUT/classpath-compile.txt" "commons-logging:commons-logging:jar:1.3.6:compile" "compile 在编译类路径上"
[ "$(wc -l <"$OUT/classpath-compile.txt" | tr -d ' ')" = 2 ] || fail "编译类路径应只有 compile 与 provided 两个依赖"
expect_line "$OUT/classpath-runtime.txt" "org.javassist:javassist:jar:3.29.2-GA:runtime" "runtime 在运行时类路径上"
if grep -q mapstruct "$OUT/classpath-runtime.txt"; then fail "provided 不应在运行时类路径上"; fi
[ "$(wc -l <"$OUT/classpath-runtime.txt" | tr -d ' ')" = 2 ] || fail "运行时类路径应只有 compile 与 runtime 两个依赖"
[ "$(wc -l <"$OUT/classpath-test.txt" | tr -d ' ')" = 4 ] || fail "测试类路径应包含全部四个依赖"
expect_line "$OUT/classpath-test.txt" "org.ow2.asm:asm:jar:9.9.1:test" "test 只在测试类路径上"
expect_regex "$OUT/run-with-runtime-classpath.txt" "NoClassDefFoundError: org/mapstruct/factory/Mappers" "只按运行时类路径运行：provided 的类找不到"
log "全部通过，输出在 $OUT"
