#!/usr/bin/env bash
# Class-File API：在 JDK 25 上生成类、改写 javac 编译的类；版本边界——JDK 25 的 API 与不同版本的 ASM 能否读 JDK 26 编译的类
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25、26 容器；约 30 秒（首次需要下载 ASM）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
J26="eclipse-temurin:26-jdk@sha256:c7a2be9d6fbbc4984b855cee02359b0e808f0ab5bcbede818970417e2407ab64"
rm -rf build/classes21 build/classes26; mkdir -p build/classes21 build/classes26
d() { docker run --rm -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$@"; }
d "$J25" javac --release 21 -d build/classes21 src/Target.java
d "$J26" javac --release 26 -d build/classes26 src/Target.java
maven_jar org.ow2.asm:asm:9.10.1 >/dev/null; maven_jar org.ow2.asm:asm:9.7.1 >/dev/null
{
  d "$J25" java src/ClassFileDemo.java build/classes21/Target.class build/classes26/Target.class | sed 's/^/jdk25./'
  d "$J26" java src/ClassFileDemo.java build/classes26/Target.class | grep '^parse' | sed 's/^/jdk26./'
  d "$J25" java -cp /cache/m2/asm-9.10.1.jar src/AsmParse.java build/classes26/Target.class | sed 's/^asm/asm-9.10.1/'
  d "$J25" java -cp /cache/m2/asm-9.7.1.jar src/AsmParse.java build/classes26/Target.class | sed 's/^asm/asm-9.7.1/'
} >"$OUT/output.tsv"
{ d "$J25" java -version 2>&1 | head -1; d "$J26" java -version 2>&1 | head -1; } >"$OUT/jdk-versions.txt"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "jdk26_image: $J26" "asm: 9.10.1、9.7.1"
f="$OUT/output.tsv"
cat "$f" >&2
expect_line "$f" "jdk25.generate	生成的类主版本号 69，调用 greet() 得到「hello from generated class」" "JDK 25：从零生成类并调用"
expect_line "$f" "jdk25.transform	读入主版本号 65 的 Target.class，改写后主版本号 65，调用 greet() 得到「new」" "JDK 25：改写 javac 编译的类，保持原版本号"
expect_line "$f" "jdk25.parse	build/classes26/Target.class：IllegalArgumentException（Unsupported class file version: 70）" "JDK 25 的 Class-File API 读不了 JDK 26 的类"
expect_line "$f" "jdk26.parse	build/classes26/Target.class：主版本号 70，解析成功" "JDK 26 的 Class-File API 能读"
expect_line "$f" "asm-9.10.1	build/classes26/Target.class：解析成功" "ASM 9.10.1 能读 JDK 26 的类"
expect_line "$f" "asm-9.7.1	build/classes26/Target.class：IllegalArgumentException（Unsupported class file major version 70）" "ASM 9.7.1 读不了"
log "全部通过，输出在 $OUT"
