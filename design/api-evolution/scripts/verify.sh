#!/usr/bin/env bash
# 四种构造方式的错误时机，以及 record 新增组件对已编译调用方的影响
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"; rm -rf build/c && mkdir -p build/c/{styles,v1,v2,client}
require_java 21
java src/styles/Styles.java >"$OUT/styles.txt"
if javac -d build/c/styles src/styles/Styles.java src/styles/Missing.java >"$OUT/type-state-missing-javac.txt" 2>&1; then fail "漏填必填项仍能编译"; fi
sed -i.bak "s#$PWD/##" "$OUT/type-state-missing-javac.txt" && rm -f "$OUT/type-state-missing-javac.txt.bak"
{
  javac -d build/c/v1 src/library-v1/lib/Notice.java
  javac -cp build/c/v1 -d build/c/client src/client/Client.java
  echo "--- run with v1"; java -cp build/c/v1:build/c/client Client
  javac -d build/c/v2 src/library-v2/lib/Notice.java
  echo "--- run with v2 (client not recompiled)"; java -cp build/c/v2:build/c/client Client 2>&1 || true
} >"$OUT/binary-compatibility.txt"
write_environment "$OUT/environment.txt"
expect_line "$OUT/styles.txt" "构造器参数写反也能编译: Rule[team=sms, channel=payment, minLevel=60, maxPerHour=2]" "同类型参数写反能编译运行"
expect_line "$OUT/styles.txt" "Builder 漏填 team，运行时: NPE team" "普通 Builder 漏填在运行时报错"
expect_line "$OUT/type-state-missing-javac.txt" "location: interface NeedTeam" "类型状态 Builder 漏填在编译期报错"
expect_line "$OUT/binary-compatibility.txt" "builder 调用方: Notice[to=ops, subject=disk, body=90%, priority=3]" "Builder 调用方在 v2 上正常运行"
expect_line "$OUT/binary-compatibility.txt" "java.lang.NoSuchMethodError: 'void lib.Notice.<init>(java.lang.String, java.lang.String, java.lang.String)'" "构造器调用方在 v2 上 NoSuchMethodError"
